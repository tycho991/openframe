package io.github.tycho991.openframe.core.library

import java.time.Instant

/**
 * Plausible capture dates (spec `photo-library`, "Implausible capture dates"; design decision 10 of
 * `library-performance`): a date before 1800 is a camera without a set clock or a date the EXIF parser rolled over, and
 * counts as missing, so the file date applies. There is no upper bound: the clock of a frame may be wrong.
 */
object CaptureDates {
    /** 1800-01-01T00:00:00Z. */
    val EARLIEST: Instant = Instant.parse("1800-01-01T00:00:00Z")

    val EARLIEST_MS: Long = EARLIEST.toEpochMilli()

    /** [instant], or `null` if it lies before [EARLIEST]. */
    fun plausible(instant: Instant?): Instant? = instant?.takeIf { !it.isBefore(EARLIEST) }

    /** Whether a stored capture date lies before [EARLIEST]. */
    fun implausible(takenAtMs: Long): Boolean = takenAtMs < EARLIEST_MS
}
