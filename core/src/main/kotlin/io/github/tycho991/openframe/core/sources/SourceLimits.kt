package io.github.tycho991.openframe.core.sources

/**
 * The fixed limits on what a source server may send (design decision 2 of `nextcloud-hardening`; spec
 * `nextcloud-source`, "Bounded answers of a server" and "Listings stay inside the folder", `remote-sources`, "Bounded
 * downloads"). The values for entries and files are sized for the heap of the smallest frame.
 */
object SourceLimits {
    private const val KIB = 1024L
    private const val MIB = 1024L * KIB

    /** One `PROPFIND` answer after gzip decompression. */
    const val LISTING_BYTES = 64 * MIB

    /** The text of one element of a listing, such as `href` or `getetag`: a long percent-encoded path fits. */
    const val ELEMENT_CHARS = 16 * 1024

    /** Entries in one `PROPFIND` answer. */
    const val ENTRIES_PER_ANSWER = 50_000

    /** Levels of subfolders below the folder of a source in one sync. */
    const val DEPTH = 32

    /** Folders listed in one sync, the folder of the source included. */
    const val FOLDERS = 5_000

    /** Files listed in one sync. */
    const val FILES = 100_000

    /** One path relative to the folder of the source. */
    const val PATH_CHARS = 1_024

    /** The paths and entity tags of all folders and files of one sync together. */
    const val LISTING_CHARS = 8_000_000L

    /** One sync: listing and downloads together, after which the sync stops and the next one goes on. */
    const val SYNC_MS = 2 * 60 * 60_000L

    /** One JSON answer of the login flow. */
    const val LOGIN_BYTES = 64 * KIB

    /** What a download may send beyond the listed size, or beyond `Content-Length` for a file without one. */
    const val DOWNLOAD_TOLERANCE_BYTES = 1 * MIB

    /** A download without a listed size and without `Content-Length`. */
    const val DOWNLOAD_UNKNOWN_SIZE_BYTES = 512 * MIB

    /** The throughput every body must reach per [WINDOW_MS], measured from its first read. */
    const val MIN_BYTES_PER_WINDOW = 16 * KIB
    const val WINDOW_MS = 60_000L

    /** The total time to read one listing answer. */
    const val LISTING_MS = 5 * 60_000L

    /** The total time to read one answer of the login flow. */
    const val LOGIN_MS = 60_000L

    /** The total time of one download. */
    const val DOWNLOAD_MS = 30 * 60_000L
}
