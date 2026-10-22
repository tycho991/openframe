package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.nextcloud.NextcloudSource
import java.io.File

/**
 * Where the original of a photo is: in the photo folder for a local photo, in the cache of downloaded originals for a
 * Nextcloud photo (fetched again first if it was removed from the cache). The render cache asks this on its worker thread.
 */
class SourceOriginals(
    private val local: LocalPhotoSource,
    private val nextcloud: NextcloudSource,
) {
    fun fileOf(entry: PhotoEntry): File = if (entry.source == NextcloudSource.SOURCE_ID) nextcloud.original(entry) else local.fileOf(entry)
}
