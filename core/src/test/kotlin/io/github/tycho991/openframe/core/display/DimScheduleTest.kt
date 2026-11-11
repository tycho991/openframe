package io.github.tycho991.openframe.core.display

import io.github.tycho991.openframe.core.settings.DisplayScheduleSettings
import io.github.tycho991.openframe.core.settings.DisplayWindow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Spec `display-schedule` › Dim periods and Dimmed during the night. 2026-10-21 is a Wednesday. */
class DimScheduleTest {
    private val utc = ZoneId.of("UTC")
    private val berlin = ZoneId.of("Europe/Berlin")
    private val defaults = DisplayScheduleSettings(enabled = true)

    private fun at(
        local: String,
        zone: ZoneId = utc,
    ): ZonedDateTime = ZonedDateTime.of(LocalDateTime.parse(local), zone)

    private fun instant(
        local: String,
        zone: ZoneId = utc,
    ): Instant = at(local, zone).toInstant()

    private fun dim(
        start: String,
        end: String,
        zone: ZoneId = utc,
    ) = DimPeriod(instant(start, zone), instant(end, zone))

    @Test
    fun `Weekday evening`() {
        val s = defaults.copy(weekdays = DisplayWindow("07:00", "22:00", "20:30"))
        assertNull(DisplaySchedule.dimAt(at("2026-10-21T20:29"), s))
        assertEquals(dim("2026-10-21T20:30", "2026-10-21T22:00"), DisplaySchedule.dimAt(at("2026-10-21T20:30"), s))
        assertEquals(dim("2026-10-21T20:30", "2026-10-21T22:00"), DisplaySchedule.dimAt(at("2026-10-21T21:59"), s))
        assertNull(DisplaySchedule.dimAt(at("2026-10-21T22:00"), s))
    }

    @Test
    fun `Dim time after midnight in a window across midnight`() {
        val s = defaults.copy(weekdays = DisplayWindow("07:00", "01:00", "00:30"))
        assertNull(DisplaySchedule.dimAt(at("2026-10-21T23:59"), s))
        assertEquals(dim("2026-10-22T00:30", "2026-10-22T01:00"), DisplaySchedule.dimAt(at("2026-10-22T00:45"), s))
        val before = defaults.copy(weekdays = DisplayWindow("07:00", "01:00", "23:30"))
        assertEquals(dim("2026-10-21T23:30", "2026-10-22T01:00"), DisplaySchedule.dimAt(at("2026-10-22T00:45"), before))
    }

    @Test
    fun `Whole-day window dims to the end of the day`() {
        val s = defaults.copy(weekend = DisplayWindow("08:00", "08:00", "21:00"))
        assertEquals(dim("2026-10-24T21:00", "2026-10-25T00:00"), DisplaySchedule.dimAt(at("2026-10-24T23:00"), s))
        assertNull(DisplaySchedule.dimAt(at("2026-10-25T00:00"), s))
        assertEquals(dim("2026-10-25T21:00", "2026-10-26T00:00"), DisplaySchedule.dimAt(at("2026-10-25T21:00"), s))
    }

    @Test
    fun `Friday's dim period followed by a weekend window`() {
        val s = defaults.copy(weekdays = DisplayWindow("07:00", "01:00", "23:00"), weekend = DisplayWindow("08:00", "23:00"))
        assertEquals(dim("2026-10-23T23:00", "2026-10-24T01:00"), DisplaySchedule.dimAt(at("2026-10-24T00:30"), s))
        assertNull(DisplaySchedule.dimAt(at("2026-10-24T22:30"), s))
        assertEquals(
            Period(false, instant("2026-10-24T01:00"), instant("2026-10-24T08:00")),
            DisplaySchedule.nightAfterDim(at("2026-10-24T03:00"), s),
        )
        assertNull(DisplaySchedule.nightAfterDim(at("2026-10-25T03:00"), s))
    }

    @Test
    fun `Dim time in the DST gap moves to after the gap`() {
        // 2026-03-29 is a Sunday; in Berlin 02:00 to 03:00 does not exist.
        val s = defaults.copy(weekend = DisplayWindow("01:00", "04:00", "02:30"))
        assertNull(DisplaySchedule.dimAt(at("2026-03-29T03:15", berlin), s))
        assertEquals(dim("2026-03-29T03:30", "2026-03-29T04:00", berlin), DisplaySchedule.dimAt(at("2026-03-29T03:45", berlin), s))
    }

    @Test
    fun `Night after a dimmed evening`() {
        val s = defaults.copy(weekdays = DisplayWindow("07:00", "22:00", "20:30"))
        assertEquals(
            Period(false, instant("2026-10-21T22:00"), instant("2026-10-22T07:00")),
            DisplaySchedule.nightAfterDim(at("2026-10-21T23:00"), s),
        )
        assertNull(DisplaySchedule.nightAfterDim(at("2026-10-21T21:00"), s))
    }

    @Test
    fun `Night after an undimmed evening`() {
        assertNull(DisplaySchedule.nightAfterDim(at("2026-10-21T23:00"), defaults))
        val weekendOnly = defaults.copy(weekend = DisplayWindow("08:00", "23:00", "22:00"))
        assertNull(DisplaySchedule.nightAfterDim(at("2026-10-21T23:00"), weekendOnly))
        assertEquals(instant("2026-10-24T23:00"), DisplaySchedule.nightAfterDim(at("2026-10-25T01:00"), weekendOnly)?.start)
    }
}
