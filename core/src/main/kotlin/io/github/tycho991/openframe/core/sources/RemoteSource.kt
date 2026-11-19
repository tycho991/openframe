package io.github.tycho991.openframe.core.sources

import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.PhotoIndexStore
import io.github.tycho991.openframe.core.library.PhotoSource
import io.github.tycho991.openframe.core.library.ScanResult
import io.github.tycho991.openframe.core.library.SkippedFile
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.Clock
import io.github.tycho991.openframe.core.platform.DecoderUnavailableException
import io.github.tycho991.openframe.core.platform.ImageException
import io.github.tycho991.openframe.core.platform.ImageFormats
import io.github.tycho991.openframe.core.platform.MediaEngine
import io.github.tycho991.openframe.core.platform.SkipReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The photos of the folder of one remote source, read only. One [scan] is one sync: it lists the folder, downloads
 * what is new or changed (an unchanged ETag means no download), works out the result outside any lock and applies it
 * to the index in one step. A sync that fails removes nothing; what was downloaded before the failure may stay.
 */
class RemoteSource(
    override val id: String,
    /** The current configuration, `null` once the source is removed. */
    private val config: () -> StoredSource?,
    private val kind: SourceKind,
    private val index: PhotoIndexStore,
    private val engine: MediaEngine,
    val originals: OriginalsStore,
    /** A photo left the library: its renderings are not needed any more. */
    private val onRemoved: (PhotoEntry) -> Unit,
    private val log: AppLog,
    /**
     * An original fetched for a rendering did not match the index (spec "Refetched original matches the index"): the
     * source is to sync soon.
     */
    private val onStale: () -> Unit = {},
    /** The most a file without a listed size and without `Content-Length` may send; smaller in a test. */
    private val unknownSizeBytes: Long = SourceLimits.DOWNLOAD_UNKNOWN_SIZE_BYTES,
    /** For the time limit of a sync, on [Clock.monotonicMillis]. */
    private val clock: Clock = Clock.System,
    /** How long one sync may take, listing and downloads together; see [SourceLimits.SYNC_MS]. */
    private val syncLimitMs: Long = SourceLimits.SYNC_MS,
) : PhotoSource {
    /**
     * One download per photo at a time: the sync and a rendering that need the same original must not share a `.part`
     * file. Striped by the photo ID, so that the number of locks stays fixed.
     */
    private val locks = Array(LOCKS) { Any() }

    private fun lockOf(entryId: String) = locks[(entryId.hashCode() and Int.MAX_VALUE) % LOCKS]

    /** The source was removed: its listings and downloads end at their next read. */
    private val cancelled: () -> Boolean = { config() == null }

    private fun limits(ended: () -> Boolean = cancelled) =
        DownloadLimits(
            roomBytes = originals::roomOnDisk,
            cancelled = ended,
            acceptHeader = { header -> ImageFormats.detect(header)?.let(engine::canDecode) ?: true },
            unknownSizeBytes = unknownSizeBytes,
        )

    override suspend fun scan(): ScanResult =
        withContext(Dispatchers.IO) {
            val thread = Thread.currentThread()
            val priority = thread.priority
            // The slideshow runs on the same few cores.
            thread.priority = Thread.NORM_PRIORITY - 2
            try {
                sync()
            } finally {
                thread.priority = priority
            }
        }

    /**
     * The original of [entry] for a rendering, fetched again from the server if it was removed from the cache. With [pin]
     * it stays through every trim until [unpin], while the renderings that wait for it are queued. A fetched original
     * whose `ETag` differs is checked by its dimensions: one that does not match the index is discarded, the rendering
     * fails, and [onStale] lets the source sync soon.
     */
    fun original(
        entry: PhotoEntry,
        pin: Boolean = false,
    ): File {
        val file = originals.fileOf(entry)
        if (pin) originals.pin(entry)
        try {
            if (file.isFile) {
                originals.touch(entry)
                return file
            }
            val config = config() ?: throw SourceException(SourceException.Kind.NOT_FOUND, "The source $id was removed")
            synchronized(lockOf(entry.id)) {
                if (!file.isFile) fetchForRendering(entry, config)
            }
            return file
        } catch (e: Throwable) {
            if (pin) originals.unpin(entry)
            throw e
        }
    }

    /** Ends the pin of [original] with `pin`. */
    fun unpin(entry: PhotoEntry) = originals.unpin(entry)

    private fun fetchForRendering(
        entry: PhotoEntry,
        config: StoredSource,
    ) {
        if (!originals.hasRoomOnDisk(entry.fileSize)) {
            throw SourceException(SourceException.Kind.STORAGE, "Not enough free storage on the frame for ${entry.path}")
        }
        val remote = kind.remoteFolder(config)
        val part = originals.partOf(entry)
        val etag =
            try {
                remote.download(RemoteFile(entry.path, entry.etag.orEmpty(), entry.fileSize, entry.modifiedMs), part, limits())
            } catch (e: Throwable) {
                part.delete()
                if (e is SourceException && e.kind == SourceException.Kind.CHANGED) stale(entry, "is larger on the server than listed")
                throw e
            }
        // The same entity tag: the file is the one in the index. Another one, or none at all, may come from a proxy or a
        // server without entity tags, so the dimensions tell.
        val sameTag = normalizedEtag(etag)?.let { it == normalizedEtag(entry.etag) } ?: false
        if (!sameTag) {
            val info =
                try {
                    engine.probe(part)
                } catch (e: Throwable) {
                    part.delete()
                    throw e
                }
            if (info.width != entry.width || info.height != entry.height) {
                part.delete()
                stale(entry, "has other dimensions on the server")
            }
        }
        originals.commit(entry)
        originals.trim(config.budgetBytes, keep = originals.fileOf(entry))
    }

    private fun stale(
        entry: PhotoEntry,
        why: String,
    ): Nothing {
        log.i(TAG, "The original of ${entry.path} in $id $why; the source syncs again")
        onStale()
        throw SourceException(SourceException.Kind.CHANGED, "${entry.path} was changed on the server; the source syncs again")
    }

    private suspend fun sync(): ScanResult {
        val config = config()
        if (config == null || !config.ready) return ScanResult(0, 0, 0, 0)
        // Android may have trimmed the cache folder since the last sync.
        originals.refresh()
        val remote = kind.remoteFolder(config)
        // A sync has a time limit as a whole, so that a slow server cannot hold the lock of all sources for days: its
        // listing and its downloads end like those of a removed source, and the next sync goes on.
        val deadline = clock.monotonicMillis() + syncLimitMs
        val overTime = { clock.monotonicMillis() > deadline }
        val ended = { cancelled() || overTime() }
        // The whole listing or an exception: nothing below runs on a part of it.
        val listing =
            try {
                remote.list(config.includeSubfolders || kind.listsFlat, ended)
            } catch (e: SourceException) {
                throw if (overTime() && config() != null) tooLong(e) else e
            }.filter {
                ImageFormats.hasImageExtension(it.path)
            }.sortedBy { it.path }
        val library = index.library.value
        val known =
            library.photos.values
                .filter { it.source == id }
                .associateBy { it.path }
        val skippedKnown = library.skipped.filter { it.source == id }.associateBy { it.path }
        val budget = config.budgetBytes

        val results = LinkedHashMap<String, Result<PhotoEntry>>()
        var failure: SourceException? = null
        try {
            for (file in listing) {
                currentCoroutineContext().ensureActive()
                if (!needsDownload(file, known[file.path], skippedKnown[file.path], budget)) continue
                if (overTime()) throw tooLong(null)
                val result =
                    try {
                        fetch(remote, file, known[file.path], ended)
                    } catch (e: SourceException) {
                        throw if (overTime() && config() != null) tooLong(e) else e
                    }
                if (result.exceptionOrNull() is DecoderUnavailableException) {
                    // Says nothing about the file, which is downloaded again at the next sync.
                    log.w(TAG, "The decoder is not available; the remaining files of $id are checked at the next sync")
                    break
                }
                results[file.path] = result
                originals.trim(budget)
            }
        } catch (e: SourceException) {
            // The folder was listed: whatever a download says, it is about a file, not about access to the folder.
            failure = e.ofFile()
        }

        val onServer = listing.mapTo(HashSet()) { it.path }
        // A listing was read completely, but if a download failed nothing may be taken away.
        val removals = config.removeVanished && failure == null
        val removed = ArrayList<PhotoEntry>()
        var added = 0
        var updated = 0
        var skipped = 0
        index.edit { edit ->
            // Removed while the sync ran: the photos of the source are gone and must not come back.
            if (config() == null) return@edit
            for ((path, result) in results) {
                result
                    .onSuccess {
                        val current = edit.get(it.id)
                        val entry = it.withStateOf(current)
                        // An original that came back for an unchanged photo: the entry is the same, nothing to apply or count.
                        if (entry == current) return@onSuccess
                        // The same photo with only its position taken over from the download: applied, but not an update.
                        if (current != null && entry.copy(gps = current.gps, metadataVersion = current.metadataVersion) == current) {
                            edit.put(entry)
                            return@onSuccess
                        }
                        if (current == null) added++ else updated++
                        edit.put(entry)
                    }.onFailure { e ->
                        // A file that vanished after the listing is not a bad file; the next sync sees it gone.
                        if (e is SourceException) return@onFailure
                        val reason = (e as? ImageException)?.reason ?: SkipReason.CORRUPT
                        if (e !is ImageException) log.w(TAG, "Error reading $path", e)
                        val file = listing.first { it.path == path }
                        edit.skip(SkippedFile(id, path, reason, file.size, file.modifiedMs))
                        skipped++
                    }
            }
            // Only what was in the index when the sync began may go: a photo that arrived meanwhile is not in `listing`.
            if (removals) {
                for (entry in edit.entries(id)) {
                    if (entry.path !in onServer && entry.path in known) {
                        edit.remove(entry.id)
                        removed += entry
                    }
                }
                for (skip in edit.skipped(id)) {
                    if (skip.path !in onServer && skip.path in skippedKnown) edit.unskip(id, skip.path)
                }
            }
        }
        removed.forEach {
            originals.remove(it.id)
            onRemoved(it)
        }
        originals.trim(budget)
        val result = ScanResult(added, updated, removed.size, skipped)
        if (result.added + result.updated + result.removed + result.skipped > 0) log.i(TAG, "Sync of $id: $result")
        failure?.let { throw it }
        return result
    }

    /** Whether [file] has to be downloaded now. */
    private fun needsDownload(
        file: RemoteFile,
        previous: PhotoEntry?,
        skip: SkippedFile?,
        budget: Long,
    ): Boolean {
        if (previous != null && previous.etag == file.etag) {
            if (originals.has(previous)) return false
            // An original that was removed comes back only while the budget has room, else a library larger than the budget
            // would be downloaded again at every run.
            return originals.fitsBudget(file.size, budget) && originals.hasRoomOnDisk(file.size)
        }
        if (skip != null && skip.fileSize == file.size && skip.modifiedMs == file.modifiedMs) return false
        if (!originals.hasRoomOnDisk(file.size)) {
            throw SourceException(SourceException.Kind.STORAGE, "Not enough free storage on the frame for ${file.path}")
        }
        return true
    }

    /** Downloads and checks [file]. A file that is not an acceptable image is a failed result, a broken connection an exception. */
    private fun fetch(
        remote: RemoteFolder,
        file: RemoteFile,
        previous: PhotoEntry?,
        ended: () -> Boolean,
    ): Result<PhotoEntry> {
        val draft =
            PhotoEntry(
                id = PhotoEntry.idFor(id, file.path),
                source = id,
                path = file.path,
                width = 0,
                height = 0,
                takenAtMs = file.modifiedMs,
                fileSize = file.size,
                modifiedMs = file.modifiedMs,
                // Hidden state, comment and section are taken over when the entry is applied (PhotoEntry.withStateOf).
                version = (previous?.version ?: 0) + 1,
                etag = file.etag,
                metadataVersion = PhotoEntry.METADATA_VERSION,
            )
        // The same ETag as before: only the original is gone from the cache, the photo is the one in the index.
        val unchanged = previous != null && previous.etag == file.etag
        synchronized(lockOf(draft.id)) {
            val part = originals.partOf(draft)
            try {
                remote.download(file, part, limits(ended))
            } catch (e: ImageException) {
                // A format the device cannot decode, told by the first bytes: skipped like a file whose probe says so.
                part.delete()
                return Result.failure(e)
            } catch (e: SourceException) {
                part.delete()
                when (e.kind) {
                    // A file that vanished since the listing is the next sync's business, not a reason to stop. A source
                    // that is gone as a whole (a withdrawn share) is: every further download would fail the same way.
                    SourceException.Kind.NOT_FOUND -> {
                        return Result.failure(e)
                    }

                    // Replaced since the listing: the next sync lists and fetches the new version.
                    SourceException.Kind.CHANGED -> {
                        log.i(TAG, "${file.path} in $id was replaced after the listing; the next sync fetches it")
                        return Result.failure(e)
                    }

                    // More than listed: a new file is skipped as too large, a photo in the library stays as it is.
                    SourceException.Kind.LIMIT -> {
                        log.w(TAG, "${e.message}")
                        return Result.failure(if (previous == null) ImageException(SkipReason.TOO_LARGE, e.message.orEmpty(), e) else e)
                    }

                    else -> {
                        throw e
                    }
                }
            }
            val info =
                try {
                    engine.probe(part)
                } catch (e: CancellationException) {
                    part.delete()
                    throw e
                } catch (e: Throwable) {
                    // Anything a fake or future engine throws, an OutOfMemoryError included, skips the file (corrupt
                    // unless it says why) instead of ending the sync.
                    part.delete()
                    return Result.failure(e)
                }
            originals.commit(draft)
            if (unchanged) {
                // The same entity tag but other dimensions: the file was replaced without a new tag, the photo is changed.
                if (info.width != previous!!.width || info.height != previous.height) {
                    log.i(TAG, "${file.path} in $id has other dimensions than in the library; it counts as changed")
                    return Result.success(fresh(draft, info, file))
                }
                // A photo from before the GPS position was read gets it with this download (spec "Metadata of photos
                // already in the index"); otherwise the entry stays as it is.
                if (previous.metadataVersion >= PhotoEntry.METADATA_VERSION) return Result.success(previous)
                return Result.success(previous.copy(gps = info.gps, metadataVersion = PhotoEntry.METADATA_VERSION))
            }
            return Result.success(fresh(draft, info, file))
        }
    }

    private fun fresh(
        draft: PhotoEntry,
        info: io.github.tycho991.openframe.core.platform.ImageInfo,
        file: RemoteFile,
    ) = draft.copy(
        width = info.width,
        height = info.height,
        takenAtMs = info.takenAt?.toEpochMilli() ?: file.modifiedMs,
        gps = info.gps,
    )

    private fun tooLong(cause: SourceException?) =
        SourceException(
            SourceException.Kind.LIMIT,
            "The sync of $id took longer than ${duration(syncLimitMs)}; the next sync goes on where it stopped",
            cause,
        )

    companion object {
        private const val TAG = "sources"

        /** `2 hours`, `90 minutes`: a time limit for a message. */
        private fun duration(ms: Long): String {
            val minutes = ms / 60_000
            return if (minutes % 60 == 0L && minutes > 0) {
                "${minutes / 60} hour${if (minutes == 60L) "" else "s"}"
            } else {
                "$minutes minute${if (minutes == 1L) "" else "s"}"
            }
        }

        private const val LOCKS = 16

        /** An entity tag as it is compared: without `W/`, quotes and the suffix a compressing proxy adds. */
        fun normalizedEtag(raw: String?): String? =
            raw
                ?.trim()
                ?.removePrefix("W/")
                ?.trim('"')
                ?.removeSuffix("-gzip")
                ?.removeSuffix("-br")
                ?.takeIf { it.isNotEmpty() }
    }
}
