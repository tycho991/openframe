package io.github.tycho991.openframe.core.sources

import io.github.tycho991.openframe.core.library.LocalPhotoSource
import io.github.tycho991.openframe.core.library.PhotoEntry
import java.io.File

/**
 * Where the original of a photo is: in the photo folder for a local photo, in the cache of downloaded originals of its
 * source for a remote photo (fetched again first if it was removed from the cache). The render cache asks this on its
 * worker thread.
 */
class SourceOriginals(
    private val local: LocalPhotoSource,
    private val sources: SourceRegistry,
) {
    fun fileOf(entry: PhotoEntry): File = if (entry.source == LocalPhotoSource.SOURCE_ID) local.fileOf(entry) else sources.original(entry)

    /** The file of [entry] if it is on the frame, without a download: `null` for a remote photo without its original. */
    fun onFrame(entry: PhotoEntry): File? =
        if (entry.source == LocalPhotoSource.SOURCE_ID) {
            local.fileOf(entry)
        } else {
            sources
                .source(entry.source)
                ?.originals
                ?.fileOf(entry)
                ?.takeIf { it.isFile }
        }
}
