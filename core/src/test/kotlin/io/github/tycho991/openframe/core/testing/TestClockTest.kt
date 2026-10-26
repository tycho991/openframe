package io.github.tycho991.openframe.core.testing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class TestClockTest {
    @Test
    fun `Advancing moves the wall clock and the monotonic time`() {
        val clock = TestClock()
        clock.advance(Duration.ofMinutes(5))
        assertEquals(Instant.parse("2026-05-01T10:20:30Z"), clock.now())
        assertEquals(300_000, clock.monotonicMillis())
    }

    @Test
    fun `A jump of the wall clock leaves the monotonic time as it was`() {
        val clock = TestClock()
        clock.advance(Duration.ofSeconds(1))
        clock.instant = Instant.parse("2031-05-01T03:00:00Z")
        assertEquals(1000, clock.monotonicMillis())
        clock.instant = Instant.parse("2021-01-01T00:00:00Z")
        assertEquals(1000, clock.monotonicMillis())
    }

    @Test
    fun `The zone can be set`() {
        val clock = TestClock(zoneId = ZoneId.of("Europe/Berlin"))
        assertEquals(ZoneId.of("Europe/Berlin"), clock.zone())
    }
}
