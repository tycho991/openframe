package io.github.tycho991.openframe.core.sources

import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.PhotoIndexStore
import io.github.tycho991.openframe.core.library.PhotoSource
import io.github.tycho991.openframe.core.library.ScanResult
import io.github.tycho991.openframe.core.library.SkippedFile
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.ImageException
import io.github.tycho991.openframe.core.platform.ImageFormats
import io.github.tycho991.openframe.core.platform.MediaEngine
import io.github.tycho991.openframe.core.platform.SkipReason
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
) : PhotoSource {
    /** Only one download writes at a time: the sync and a rendering that needs an original must not share a `.part` file. */
    private val downloadLock = Any()

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

    /** The original of [entry] for a rendering, fetched again from the server if it was removed from the cache. */
    fun original(entry: PhotoEntry): File {
        val file = originals.fileOf(entry)
        if (file.isFile) {
            originals.touch(entry)
            return file
        }
        val config = config() ?: throw SourceException(SourceException.Kind.NOT_FOUND, "The source $id was removed")
        synchronized(downloadLock) {
            if (!file.isFile) {
                if (!originals.hasRoomOnDisk(entry.fileSize)) {
                    throw SourceException(SourceException.Kind.STORAGE, "Not enough free storage on the frame for ${entry.path}")
                }
                val remote = kind.remoteFolder(config)
                remote.download(RemoteFile(entry.path, entry.etag.orEmpty(), entry.fileSize, entry.modifiedMs), originals.partOf(entry))
                originals.commit(entry)
                originals.trim(config.budgetBytes, keep = file)
            }
        }
        return file
    }

    private suspend fun sync(): ScanResult {
        val config = config()
        if (config == null || !config.ready) return ScanResult(0, 0, 0, 0)
        val remote = kind.remoteFolder(config)
        // The whole listing or an exception: nothing below runs on a part of it.
        val listing =
            remote
                .list(config.includeSubfolders || kind.listsFlat)
                .filter {
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
                results[file.path] = fetch(remote, file, known[file.path])
                originals.trim(budget)
            }
        } catch (e: SourceException) {
            failure = e
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
                        if (current == null) added++ else updated++
                        edit.put(entry)
                    }.onFailure { e ->
                        // A file that vanished after the listing is not a bad file; the next sync sees it gone.
                        if (e is SourceException) return@onFailure
                        val reason = (e as? ImageException)?.reason ?: SkipReason.CORRUPT
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
        synchronized(downloadLock) {
            val part = originals.partOf(draft)
            try {
                remote.download(file, part)
            } catch (e: SourceException) {
                part.delete()
                // A file that vanished since the listing is the next sync's business, not a reason to stop. A source that is
                // gone as a whole (a withdrawn share) is: every further download would fail the same way.
                if (e.kind == SourceException.Kind.NOT_FOUND) return Result.failure(e)
                throw e
            }
            val info =
                try {
                    engine.probe(part)
                } catch (e: ImageException) {
                    part.delete()
                    return Result.failure(e)
                }
            originals.commit(draft)
            if (unchanged) {
                // A photo from before the GPS position was read gets it with this download (spec "Metadata of photos
                // already in the index"); otherwise the entry stays as it is.
                if (previous!!.metadataVersion >= PhotoEntry.METADATA_VERSION) return Result.success(previous)
                return Result.success(previous.copy(gps = info.gps, metadataVersion = PhotoEntry.METADATA_VERSION))
            }
            return Result.success(
                draft.copy(
                    width = info.width,
                    height = info.height,
                    takenAtMs =
                        info.takenAt?.toEpochMilli() ?: file.modifiedMs,
                    gps = info.gps,
                ),
            )
        }
    }

    private companion object {
        const val TAG = "sources"
    }
}
