package io.github.tycho991.openframe.core.sources

import java.io.File

/** A file in the folder of a source. [path] is relative to the configured folder, separated by `/`. */
data class RemoteFile(
    val path: String,
    /** The ETag without quotes: it changes whenever the file does. */
    val etag: String,
    val size: Long,
    val modifiedMs: Long,
)

/**
 * The configured folder of a source on its server. An interface so that the sync is tested without a server and
 * without sockets, and so that each kind of source brings its own (the WebDAV folders of Nextcloud today).
 */
interface RemoteFolder {
    /**
     * All files below the folder, also those in subfolders if [recursive], read completely or not at all: a folder that
     * cannot be read ends in a [SourceException], never in a shorter list.
     */
    fun list(recursive: Boolean): List<RemoteFile>

    /** Writes the content of [file] to [target]. A failure leaves nothing the caller must trust. */
    fun download(
        file: RemoteFile,
        target: File,
    )
}
