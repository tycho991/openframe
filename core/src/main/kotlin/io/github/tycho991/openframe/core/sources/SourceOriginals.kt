package io.github.tycho991.openframe.core.sources

import io.github.tycho991.openframe.core.library.LocalPhotoSource
import io.github.tycho991.openframe.core.library.OriginalRequest
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.RenderFailedException
import io.github.tycho991.openframe.core.library.TrashItem
import io.github.tycho991.openframe.core.library.TrashStore
import java.io.File

/**
 * Where the original of a photo is: in the photo folder for a local photo, in the cache of downloaded originals of its
 * source for a remote photo. The render cache asks [onFrame] on its worker thread and [fileOf] on its download thread,
 * so that a missing original never holds up the renderings of other photos.
 */
class SourceOriginals(
    private val local: LocalPhotoSource,
    private val sources: SourceRegistry,
    /** The trash, whose thumbnails render from the file in their entry ([TrashItem.SOURCE]); nothing to download. */
    private val trash: TrashStore? = null,
) {
    /**
     * The file of [entry], fetched again first if it was removed from the cache. A remote original stays pinned until
     * [release], so that a small budget cannot remove it before the renderings that wait for it are done. [request] says
     * whether a rendering somebody awaits needs it and when nobody wants it any more.
     */
    fun fileOf(
        entry: PhotoEntry,
        request: OriginalRequest = OriginalRequest(onDemand = false) { false },
    ): File =
        when (entry.source) {
            LocalPhotoSource.SOURCE_ID -> local.fileOf(entry)
            TrashItem.SOURCE -> trashFile(entry) ?: throw RenderFailedException("The trash entry ${entry.id} is gone")
            else -> sources.original(entry, pin = true, onDemand = request.onDemand, ended = request.cancelled)
        }

    private fun trashFile(entry: PhotoEntry): File? = trash?.fileOf(entry)

    /** Ends the pin that [fileOf] set. */
    fun release(entry: PhotoEntry) {
        if (entry.source != LocalPhotoSource.SOURCE_ID && entry.source != TrashItem.SOURCE) sources.source(entry.source)?.unpin(entry)
    }

    /**
     * The file of [entry] if it is on the frame, without a download: `null` for a remote photo without its original. A
     * remote original found for a rendering ([markUsed]) is marked as used, as a fetched one is, so that the most shown
     * are not the first removed.
     */
    fun onFrame(
        entry: PhotoEntry,
        markUsed: Boolean = true,
    ): File? =
        when (entry.source) {
            LocalPhotoSource.SOURCE_ID -> {
                local.fileOf(entry)
            }

            TrashItem.SOURCE -> {
                trashFile(entry)
            }

            else -> {
                sources
                    .source(entry.source)
                    ?.originals
                    ?.let { store ->
                        store.fileOf(entry).takeIf { it.isFile }?.also { if (markUsed) store.touch(entry) }
                    }
            }
        }
}
