package io.github.tycho991.openframe.core.nextcloud

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
 * The photos of a Nextcloud folder, read only. One [scan] is one sync: it lists the folder, downloads what is new or
 * changed (an unchanged ETag means no download), works out the result outside any lock and applies it to the index in
 * one step. A sync that fails removes nothing; what was downloaded before the failure may stay.
 */
class NextcloudSource(
    private val configs: NextcloudConfigStore,
    private val clients: NextcloudClients,
    private val index: PhotoIndexStore,
    private val engine: MediaEngine,
    val originals: OriginalsStore,
    /** A photo left the library: its renderings are not needed any more. */
    private val onRemoved: (PhotoEntry) -> Unit,
    private val log: AppLog,
) : PhotoSource {
    override val id = SOURCE_ID

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
        val config = configs.config.value
        if (!config.connected) throw NextcloudException(NextcloudException.Kind.UNAUTHORIZED, "The Nextcloud source is not connected")
        synchronized(downloadLock) {
            if (!file.isFile) {
                if (!originals.hasRoomOnDisk(entry.fileSize)) {
                    throw NextcloudException(NextcloudException.Kind.STORAGE, "Not enough free storage on the frame for ${entry.path}")
                }
                val remote = clients.remoteFolder(config)
                remote.download(RemoteFile(entry.path, entry.etag.orEmpty(), entry.fileSize, entry.modifiedMs), originals.partOf(entry))
                originals.commit(entry)
                originals.trim(budgetBytes(config), keep = file)
            }
        }
        return file
    }

    /**
     * Removes everything of this source: credentials (after trying to revoke the app password on the server), photos,
     * skipped files and originals. Local photos are not touched.
     */
    fun disconnect() {
        val config = configs.config.value
        if (config.connected) {
            try {
                clients.loginFlow().revoke(config.serverUrl, config.loginName, config.appPassword)
            } catch (e: Exception) {
                log.w(TAG, "The app password could not be revoked on the server: ${e.message}")
            }
        }
        configs.disconnect()
        val removed =
            index.edit { edit ->
                val gone = edit.entries(id)
                gone.forEach { edit.remove(it.id) }
                edit.skipped(id).forEach { edit.unskip(id, it.path) }
                gone
            }
        removed.forEach(onRemoved)
        originals.clear()
        log.i(TAG, "Disconnected, ${removed.size} photos removed")
    }

    private suspend fun sync(): ScanResult {
        val config = configs.config.value
        if (!config.connected || !config.enabled) return ScanResult(0, 0, 0, 0)
        val remote = clients.remoteFolder(config)
        // The whole listing or an exception: nothing below runs on a part of it.
        val listing = remote.list(config.includeSubfolders).filter { ImageFormats.hasImageExtension(it.path) }.sortedBy { it.path }
        val library = index.library.value
        val known =
            library.photos.values
                .filter { it.source == id }
                .associateBy { it.path }
        val skippedKnown = library.skipped.filter { it.source == id }.associateBy { it.path }
        val budget = budgetBytes(config)

        val results = LinkedHashMap<String, Result<PhotoEntry>>()
        var failure: NextcloudException? = null
        try {
            for (file in listing) {
                currentCoroutineContext().ensureActive()
                if (!needsDownload(file, known[file.path], skippedKnown[file.path], budget)) continue
                results[file.path] = fetch(remote, file, known[file.path])
                originals.trim(budget)
            }
        } catch (e: NextcloudException) {
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
            for ((path, result) in results) {
                result
                    .onSuccess {
                        if (edit.get(it.id) == null) added++ else updated++
                        edit.put(it)
                    }.onFailure { e ->
                        // A file that vanished after the listing is not a bad file; the next sync sees it gone.
                        if (e is NextcloudException) return@onFailure
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
        if (result.added + result.updated + result.removed + result.skipped > 0) log.i(TAG, "Sync: $result")
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
            throw NextcloudException(NextcloudException.Kind.STORAGE, "Not enough free storage on the frame for ${file.path}")
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
                version = (previous?.version ?: 0) + 1,
                hidden = previous?.hidden ?: false,
                etag = file.etag,
            )
        synchronized(downloadLock) {
            val part = originals.partOf(draft)
            try {
                remote.download(file, part)
            } catch (e: NextcloudException) {
                part.delete()
                // A file that vanished since the listing is the next sync's business, not a reason to stop.
                if (e.kind == NextcloudException.Kind.NOT_FOUND) return Result.failure(e)
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
            return Result.success(
                draft.copy(
                    width = info.width,
                    height = info.height,
                    takenAtMs =
                        info.takenAt?.toEpochMilli() ?: file.modifiedMs,
                ),
            )
        }
    }

    private fun budgetBytes(config: StoredNextcloud) = config.originalsBudgetMb * 1024L * 1024L

    companion object {
        const val SOURCE_ID = "nextcloud"
        private const val TAG = "nextcloud"
    }
}
