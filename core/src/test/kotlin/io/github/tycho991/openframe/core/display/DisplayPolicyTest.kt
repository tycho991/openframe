package io.github.tycho991.openframe.core.display

import io.github.tycho991.openframe.core.settings.DisplayScheduleSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

/** Design decision 4: the order of precedence, with `cause` and `nextChange`. 2026-10-21 is a Wednesday. */
class DisplayPolicyTest {
    private val schedule = DisplayScheduleSettings(enabled = true)
    private val off = DisplayScheduleSettings(enabled = false)
    private val mono = 1_000_000L

    private val wednesdayDay = Instant.parse("2026-10-21T15:00:00Z")
    private val wednesdayNight = Instant.parse("2026-10-21T23:00:00Z")
    private val dayPeriod = Period(true, Instant.parse("2026-10-21T07:00:00Z"), Instant.parse("2026-10-21T22:00:00Z"))
    private val nightPeriod = Period(false, Instant.parse("2026-10-21T22:00:00Z"), Instant.parse("2026-10-22T07:00:00Z"))

    private fun decide(
        now: Instant,
        schedule: DisplayScheduleSettings = this.schedule,
        available: Boolean = true,
        override: Override? = null,
        wakeUntil: Long? = null,
    ) = DisplayPolicy.decide(DisplayInputs(schedule, available, override, wakeUntil), now, ZoneOffset.UTC, mono)

    @Test
    fun `Not available decides nothing, whatever else is set`() {
        assertEquals(
            Decision(null, DisplayCause.NONE, null, null),
            decide(wednesdayNight, available = false, override = Override(false, nightPeriod), wakeUntil = mono + 60_000),
        )
    }

    @Test
    fun `An override in its period decides`() {
        assertEquals(
            Decision(false, DisplayCause.MANUAL, Instant.parse("2026-10-22T07:00:00Z"), dayPeriod),
            decide(wednesdayDay, override = Override(false, dayPeriod), wakeUntil = mono + 60_000),
        )
    }

    @Test
    fun `On at night holds until the off time of the following evening`() {
        assertEquals(
            Decision(true, DisplayCause.MANUAL, Instant.parse("2026-10-22T22:00:00Z"), nightPeriod),
            decide(wednesdayNight, override = Override(true, nightPeriod)),
        )
    }

    @Test
    fun `An override of the same value ends at the period end`() {
        assertEquals(
            Decision(true, DisplayCause.MANUAL, dayPeriod.end, dayPeriod),
            decide(wednesdayDay, override = Override(true, dayPeriod)),
        )
    }

    @Test
    fun `An override ends at the next period`() {
        // Set by day, now it is night: the schedule decides again.
        assertEquals(
            Decision(false, DisplayCause.SCHEDULE, nightPeriod.end, nightPeriod),
            decide(wednesdayNight, override = Override(true, dayPeriod)),
        )
    }

    @Test
    fun `An override set without a schedule ends when the schedule is switched on`() {
        assertEquals(
            Decision(false, DisplayCause.SCHEDULE, nightPeriod.end, nightPeriod),
            decide(wednesdayNight, override = Override(true, null)),
        )
    }

    @Test
    fun `An override without a schedule holds without an end`() {
        assertEquals(
            Decision(false, DisplayCause.MANUAL, null, null),
            decide(wednesdayDay, schedule = off, override = Override(false, null)),
        )
        // Also when it was set while a schedule was active, which was switched off since.
        assertEquals(
            Decision(false, DisplayCause.MANUAL, null, null),
            decide(wednesdayDay, schedule = off, override = Override(false, dayPeriod)),
        )
    }

    @Test
    fun `Waking keeps the display on until wakeUntil`() {
        assertEquals(
            Decision(true, DisplayCause.WAKE, wednesdayNight.plusSeconds(300), nightPeriod),
            decide(wednesdayNight, wakeUntil = mono + 300_000),
        )
    }

    @Test
    fun `A waking period that has passed lets the schedule decide`() {
        assertEquals(Decision(false, DisplayCause.SCHEDULE, nightPeriod.end, nightPeriod), decide(wednesdayNight, wakeUntil = mono))
    }

    @Test
    fun `nextChange is the earliest of the period end and wakeUntil`() {
        val early = Instant.parse("2026-10-22T06:50:00Z")
        assertEquals(Instant.parse("2026-10-22T06:55:00Z"), decide(early, wakeUntil = mono + 300_000).nextChange)
        // Waking that lasts into the on period: the display stays on, and changes next at the end of that period.
        assertEquals(Instant.parse("2026-10-22T22:00:00Z"), decide(early, wakeUntil = mono + 900_000).nextChange)
    }

    @Test
    fun `The schedule decides in its on period, also while woken`() {
        assertEquals(Decision(true, DisplayCause.SCHEDULE, dayPeriod.end, dayPeriod), decide(wednesdayDay, wakeUntil = mono + 300_000))
    }

    @Test
    fun `The schedule decides in its off period`() {
        assertEquals(Decision(false, DisplayCause.SCHEDULE, nightPeriod.end, nightPeriod), decide(wednesdayNight))
    }

    @Test
    fun `A clock before the floor decides nothing, by night and by day`() {
        // Saturday, 15 May 2021: the Frameo's clock after a boot without network.
        for (now in listOf("2021-05-15T00:00:00Z", "2021-05-15T10:00:00Z", "2025-12-31T23:59:59Z")) {
            assertEquals(Decision(null, DisplayCause.NONE, null, null), decide(Instant.parse(now)), now)
        }
        assertEquals(DisplayCause.SCHEDULE, decide(DisplayPolicy.CLOCK_FLOOR).cause, "the floor itself counts as set")
    }

    @Test
    fun `Without a schedule the frame decides nothing`() {
        assertEquals(Decision(null, DisplayCause.NONE, null, null), decide(wednesdayNight, schedule = off, wakeUntil = mono + 300_000))
    }
}
