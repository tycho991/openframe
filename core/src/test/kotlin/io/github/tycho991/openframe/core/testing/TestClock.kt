package io.github.tycho991.openframe.core.testing

import io.github.tycho991.openframe.core.platform.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Controllable clock for tests. [advance] moves the wall clock, the monotonic time and the uptime together; setting
 * [instant] directly is a jump of the wall clock that leaves the others as they were. Setting [elapsed] lower than
 * before is a restart of the device.
 */
class TestClock(
    var instant: Instant = Instant.parse("2026-05-01T10:15:30Z"),
    var zoneId: ZoneId = ZoneOffset.UTC,
) : Clock {
    var monotonic: Long = 0

    /** The device's uptime, [Clock.elapsedRealtimeMillis]. */
    var elapsed: Long = 0

    override fun now(): Instant = instant

    override fun zone(): ZoneId = zoneId

    override fun monotonicMillis(): Long = monotonic

    override fun elapsedRealtimeMillis(): Long = elapsed

    fun advance(duration: Duration) {
        instant += duration
        monotonic += duration.toMillis()
        elapsed += duration.toMillis()
    }
}
