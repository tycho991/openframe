package io.github.tycho991.openframe.core.testing

import io.github.tycho991.openframe.core.platform.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Controllable clock for tests. [advance] moves the wall clock and the monotonic time together; setting [instant]
 * directly is a jump of the wall clock that leaves the monotonic time as it was.
 */
class TestClock(
    var instant: Instant = Instant.parse("2026-05-01T10:15:30Z"),
    var zoneId: ZoneId = ZoneOffset.UTC,
) : Clock {
    var monotonic: Long = 0

    override fun now(): Instant = instant

    override fun zone(): ZoneId = zoneId

    override fun monotonicMillis(): Long = monotonic

    fun advance(duration: Duration) {
        instant += duration
        monotonic += duration.toMillis()
    }
}
