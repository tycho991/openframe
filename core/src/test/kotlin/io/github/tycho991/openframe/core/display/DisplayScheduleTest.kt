package io.github.tycho991.openframe.core.display

import io.github.tycho991.openframe.core.settings.DisplayScheduleSettings
import io.github.tycho991.openframe.core.settings.DisplayWindow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Spec `display-schedule` › Periods of the schedule. 2026-10-21 is a Wednesday. */
class DisplayScheduleTest {
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

    private fun period(
        on: Boolean,
        start: String,
        end: String,
        zone: ZoneId = utc,
    ) = Period(on, instant(start, zone), instant(end, zone))

    @Test
    fun `Weekday evening`() {
        assertEquals(period(true, "2026-10-21T07:00", "2026-10-21T22:00"), DisplaySchedule.periodAt(at("2026-10-21T21:59"), defaults))
        assertEquals(period(false, "2026-10-21T22:00", "2026-10-22T07:00"), DisplaySchedule.periodAt(at("2026-10-21T22:00"), defaults))
    }

    @Test
    fun `Friday night`() {
        val s = defaults.copy(weekdays = DisplayWindow("07:00", "01:00"))
        assertEquals(period(true, "2026-10-23T07:00", "2026-10-24T01:00"), DisplaySchedule.periodAt(at("2026-10-24T00:30"), s))
        assertEquals(instant("2026-10-24T01:00"), DisplaySchedule.periodAt(at("2026-10-24T00:30"), s).end)
        assertEquals(period(false, "2026-10-24T01:00", "2026-10-24T08:00"), DisplaySchedule.periodAt(at("2026-10-24T01:00"), s))
    }

    @Test
    fun `Weekend morning`() {
        assertEquals(period(false, "2026-10-23T22:00", "2026-10-24T08:00"), DisplaySchedule.periodAt(at("2026-10-24T07:30"), defaults))
        assertEquals(instant("2026-10-24T08:00"), DisplaySchedule.periodAt(at("2026-10-24T07:30"), defaults).end)
    }

    @Test
    fun `Same on and off time`() {
        val s = defaults.copy(weekend = DisplayWindow("08:00", "08:00"))
        val weekend = period(true, "2026-10-24T00:00", "2026-10-26T00:00")
        assertEquals(weekend, DisplaySchedule.periodAt(at("2026-10-24T00:00"), s))
        assertEquals(weekend, DisplaySchedule.periodAt(at("2026-10-25T07:30"), s))
        assertEquals(weekend, DisplaySchedule.periodAt(at("2026-10-25T23:59"), s))
        assertEquals(period(false, "2026-10-26T00:00", "2026-10-26T07:00"), DisplaySchedule.periodAt(at("2026-10-26T00:00"), s))
        assertEquals(period(false, "2026-10-23T22:00", "2026-10-24T00:00"), DisplaySchedule.periodAt(at("2026-10-23T23:00"), s))
    }

    @Test
    fun `A Sunday window crossing into Monday`() {
        val s = defaults.copy(weekend = DisplayWindow("08:00", "02:00"))
        assertEquals(period(true, "2026-10-25T08:00", "2026-10-26T02:00"), DisplaySchedule.periodAt(at("2026-10-26T01:00"), s))
        assertEquals(period(false, "2026-10-26T02:00", "2026-10-26T07:00"), DisplaySchedule.periodAt(at("2026-10-26T02:00"), s))
        // Saturday's window crosses into Sunday as well.
        assertEquals(period(true, "2026-10-24T08:00", "2026-10-25T02:00"), DisplaySchedule.periodAt(at("2026-10-25T01:59"), s))
    }

    @Test
    fun `Equal times on a weekday cover the weekdays`() {
        val s = defaults.copy(weekdays = DisplayWindow("07:00", "07:00"))
        // Monday 00:00 to Saturday 00:00, then the weekend window.
        assertEquals(period(true, "2026-10-19T00:00", "2026-10-24T00:00"), DisplaySchedule.periodAt(at("2026-10-21T03:00"), s))
        assertEquals(period(false, "2026-10-24T00:00", "2026-10-24T08:00"), DisplaySchedule.periodAt(at("2026-10-24T03:00"), s))
        assertEquals(period(false, "2026-10-25T23:00", "2026-10-26T00:00"), DisplaySchedule.periodAt(at("2026-10-25T23:30"), s))
    }

    @Test
    fun `Whole days everywhere never switch`() {
        val s = defaults.copy(weekdays = DisplayWindow("07:00", "07:00"), weekend = DisplayWindow("10:00", "10:00"))
        assertEquals(Period(true, null, null), DisplaySchedule.periodAt(at("2026-10-21T03:00"), s))
        assertEquals(null, DisplaySchedule.periodAt(at("2026-10-21T03:00"), s).end)
    }

    @Test
    fun `Adjacent windows are one period`() {
        // Friday until Saturday 08:00, then Saturday from 08:00: no switching time in between.
        val s = defaults.copy(weekdays = DisplayWindow("09:00", "08:00"), weekend = DisplayWindow("08:00", "23:00"))
        assertEquals(period(true, "2026-10-23T09:00", "2026-10-24T23:00"), DisplaySchedule.periodAt(at("2026-10-24T07:59"), s))
    }

    @Test
    fun `On time in the gap of the spring DST change`() {
        // 2026-03-29 is a Sunday; in Berlin 02:00 CET jumps to 03:00 CEST, so 02:30 does not exist and becomes 03:30.
        val s = defaults.copy(weekend = DisplayWindow("02:30", "23:00"))
        val start = instant("2026-03-29T03:30", berlin)
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), start)
        assertEquals(
            Period(false, instant("2026-03-28T23:00", berlin), start),
            DisplaySchedule.periodAt(at("2026-03-29T03:00", berlin), s),
        )
        assertEquals(
            Period(true, start, instant("2026-03-29T23:00", berlin)),
            DisplaySchedule.periodAt(at("2026-03-29T03:30", berlin), s),
        )
    }

    @Test
    fun `Off time in the doubled hour of the autumn DST change`() {
        // 2026-10-25 is a Sunday; in Berlin 03:00 CEST goes back to 02:00 CET, so 02:30 happens twice. The first counts.
        val s = defaults.copy(weekend = DisplayWindow("08:00", "02:30"))
        val end = Instant.parse("2026-10-25T00:30:00Z")
        val saturday = Period(true, instant("2026-10-24T08:00", berlin), end)
        assertEquals(saturday, DisplaySchedule.periodAt(ZonedDateTime.ofInstant(Instant.parse("2026-10-25T00:29:00Z"), berlin), s))
        val night = Period(false, end, instant("2026-10-25T08:00", berlin))
        assertEquals(night, DisplaySchedule.periodAt(ZonedDateTime.ofInstant(Instant.parse("2026-10-25T00:30:00Z"), berlin), s))
        // The second 02:20, in CET.
        assertEquals(night, DisplaySchedule.periodAt(ZonedDateTime.ofInstant(Instant.parse("2026-10-25T01:20:00Z"), berlin), s))
    }

    @Test
    fun `The local time of the zone counts`() {
        // 21:30 UTC is 23:30 in Berlin in summer: off there, still on in UTC.
        val moment = Instant.parse("2026-07-15T21:30:00Z")
        assertEquals(false, DisplaySchedule.periodAt(ZonedDateTime.ofInstant(moment, berlin), defaults).on)
        assertEquals(true, DisplaySchedule.periodAt(ZonedDateTime.ofInstant(moment, utc), defaults).on)
    }
}
