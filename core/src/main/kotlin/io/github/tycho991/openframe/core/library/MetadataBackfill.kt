package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.DecoderUnavailableException
import io.github.tycho991.openframe.core.platform.ImageMetadata
import io.github.tycho991.openframe.core.platform.MediaEngine
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads the metadata of photos that came into the index before it was read, once, in the background (spec
 * `photo-library`, "Metadata of photos already in the index"): the entries whose [PhotoEntry.metadataVersion] is below
 * [PhotoEntry.METADATA_VERSION]. Only the EXIF data is read ([MediaEngine.readMetadata]), nothing is decoded or rendered.
 *
 * A run reads [batchSize] entries at a time outside the index lock and applies each batch with one edit, so the library
 * version changes once per batch. An entry is written only if its image version is still the one that was read; a file
 * that changed meanwhile keeps the result of its own new read. A file that is there but cannot be read (damaged) gets
 * the current version without a position, so it is never tried again. A photo whose file is not on the frame ([fileOf]
 * gives `null` or a missing file: a remote photo without its original, a local file that is gone or not mounted yet)
 * is left as it is: the next download brings the position, and the next scan removes a file that is gone for good.
 */
class MetadataBackfill(
    private val index: PhotoIndexStore,
    private val engine: MediaEngine,
    /** The file of a photo on the frame, without a download; `null` if it is not here. */
    private val fileOf: (PhotoEntry) -> File?,
    private val log: AppLog,
    private val batchSize: Int = BATCH_SIZE,
    /** Runs the work; by default one thread of its own at minimum priority, so it yields to the slideshow. */
    private val executor: Executor = defaultExecutor(),
) {
    private val scheduled = AtomicBoolean(false)

    /**
     * Asks for a run, when the library is ready and after each scan and sync. Requests while one is waiting are merged;
     * one while a run is going makes another run follow it.
     */
    fun request() {
        if (!scheduled.compareAndSet(false, true)) return
        try {
            executor.execute {
                scheduled.set(false)
                try {
                    run()
                } catch (e: Throwable) {
                    log.e(TAG, "Reading the metadata of the photos in the index failed", e)
                }
            }
        } catch (e: RejectedExecutionException) {
            // Closed: the core is stopping, and a sync that ends after it has nothing left to start.
            scheduled.set(false)
        }
    }

    /**
     * After a scan of the photo folder. Only a scan that changed something can bring a photo to read; every other one
     * would only go through the photos that wait for their original again, each with a look at the disk.
     */
    fun scanned(result: ScanResult) {
        if (result.added + result.updated + result.removed + result.skipped > 0) request()
    }

    /** Stops the thread; a batch that is being read is not applied. */
    fun close() {
        (executor as? ExecutorService)?.shutdownNow()
    }

    private fun run() {
        val due =
            index.library.value.photos.values
                .filter { it.metadataVersion < PhotoEntry.METADATA_VERSION }
                .sortedBy { it.id }
        if (due.isEmpty()) return
        var read = 0
        var unavailable = false
        for (batch in due.chunked(batchSize)) {
            if (Thread.currentThread().isInterrupted) return
            val results =
                batch.mapNotNull { entry ->
                    if (unavailable) return@mapNotNull null
                    val file = fileOf(entry)?.takeIf { it.isFile } ?: return@mapNotNull null
                    val metadata =
                        try {
                            engine.readMetadata(file)
                        } catch (e: DecoderUnavailableException) {
                            // Not marked as read: the next run reads it once the decoder is there.
                            unavailable = true
                            return@mapNotNull null
                        } catch (e: Throwable) {
                            // A crash of the decoder included: the photo is marked as read without a position.
                            log.w(TAG, "Metadata of ${entry.path} not readable: ${e.message}")
                            ImageMetadata.NONE
                        }
                    entry to metadata
                }
            if (results.isEmpty()) continue
            if (Thread.currentThread().isInterrupted) return
            index.edit { edit ->
                for ((entry, metadata) in results) {
                    val current = edit.get(entry.id) ?: continue
                    if (current.version != entry.version || current.metadataVersion >= PhotoEntry.METADATA_VERSION) continue
                    edit.put(current.copy(gps = metadata.gps, metadataVersion = PhotoEntry.METADATA_VERSION))
                }
            }
            read += results.size
            if (unavailable) {
                log.w(TAG, "The decoder is not available; the metadata of the remaining photos is read at the next run")
                break
            }
        }
        if (read > 0) log.i(TAG, "Metadata of $read photos read, ${due.size - read} left for their next download")
    }

    companion object {
        const val BATCH_SIZE = 50
        private const val TAG = "backfill"

        private fun defaultExecutor(): ExecutorService =
            Executors.newSingleThreadExecutor { work ->
                Thread(work, "openframe-backfill").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }
    }
}
