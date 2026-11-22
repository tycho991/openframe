package io.github.tycho991.openframe.core.library

/**
 * The frame's storage reserve (spec `photo-library`, "Storage reserve"; design decision 1 of `library-performance`):
 * Android's low-storage threshold of Android 5 and 6, 10 % of the data volume and at most 500 MiB, plus 100 MiB.
 * Uploads, downloads of originals and renderings ahead of time leave it free. On Android 5 and 6, AOSP starts trimming
 * app caches at about three quarters of the way from the threshold to "full"; a frame that keeps the reserve free stays
 * above that point. It is the frame's own value and does not follow the device's setting at run time.
 */
object StorageReserve {
    const val MIB = 1024L * 1024

    /** The low-storage threshold of Android 5 and 6 is at most this. */
    const val THRESHOLD_MAX_BYTES = 500 * MIB

    /** What the frame keeps free above the threshold. */
    const val MARGIN_BYTES = 100 * MIB

    /** The reserve on a data volume of [totalBytes]; 0 or less, an unknown size, gives the margin alone. */
    fun bytes(totalBytes: Long): Long = minOf(totalBytes.coerceAtLeast(0) / 10, THRESHOLD_MAX_BYTES) + MARGIN_BYTES
}
