package io.github.tycho991.openframe.core.sources

import java.io.File

/** A file in the folder of a source. [path] is relative to the configured folder, separated by `/`. */
data class RemoteFile(
    val path: String,
    /** The ETag without quotes: it changes whenever the file does. */
    val etag: String,
    /** The size the listing gave, `0` if it gave none. */
    val size: Long,
    val modifiedMs: Long,
)

/**
 * The bounds of one download (spec `remote-sources`, "Bounded downloads"), besides the listed size plus a tolerance that
 * the folder enforces itself.
 */
class DownloadLimits(
    /** The free storage the download may take: what is free minus the reserve, asked when the download starts. */
    val roomBytes: () -> Long = { Long.MAX_VALUE },
    /** `true` once the source is removed: the download ends at its next read. */
    val cancelled: () -> Boolean = { false },
    /** Whether the first bytes of the file show a format the device can decode; `false` ends the download. */
    val acceptHeader: (ByteArray) -> Boolean = { true },
    /** The most a file without a listed size and without `Content-Length` may send. */
    val unknownSizeBytes: Long = SourceLimits.DOWNLOAD_UNKNOWN_SIZE_BYTES,
)

/**
 * The configured folder of a source on its server. An interface so that the sync is tested without a server and
 * without sockets, and so that each kind of source brings its own (the WebDAV folders of Nextcloud today).
 */
interface RemoteFolder {
    /**
     * All files below the folder, also those in subfolders if [recursive], read completely or not at all: a folder that
     * cannot be read ends in a [SourceException], never in a shorter list. [cancelled] ends it once the source is removed.
     */
    fun list(
        recursive: Boolean,
        cancelled: () -> Boolean = { false },
    ): List<RemoteFile>

    /**
     * Writes the content of [file] to [target] within [limits] and returns the `ETag` header of the answer, `null` if it
     * had none. A failure leaves nothing the caller must trust: a [SourceException], or an
     * [io.github.tycho991.openframe.core.platform.ImageException] for a format that [DownloadLimits.acceptHeader] refused.
     * More bytes than listed are [SourceException.Kind.LIMIT]; a `Content-Length` clearly above the listed size is
     * [SourceException.Kind.CHANGED].
     */
    fun download(
        file: RemoteFile,
        target: File,
        limits: DownloadLimits = DownloadLimits(),
    ): String?
}
