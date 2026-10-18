package io.github.tycho991.openframe.core.testing

import io.github.tycho991.openframe.core.platform.Clock
import java.time.Duration
import java.time.Instant

/** Steuerbare Uhr für Tests. */
class TestClock(
    var instant: Instant = Instant.parse("2026-05-01T10:15:30Z"),
) : Clock {
    override fun now(): Instant = instant

    fun advance(duration: Duration) {
        instant += duration
    }
}
