package io.github.tycho991.openframe.core.display

import io.github.tycho991.openframe.core.settings.DisplayScheduleSettings
import io.github.tycho991.openframe.core.settings.DisplayWindow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A stretch of time in which the schedule wants the display [on] or off, from [start] until [end]. `null` is
 * unbounded: a schedule whose windows all cover the whole day is one endless on period.
 */
data class Period(
    val on: Boolean,
    val start: Instant?,
    val end: Instant?,
)

/**
 * When the schedule wants the display on (spec `display-schedule` › Periods of the schedule). Pure, so that it is
 * tested without time passing.
 *
 * The on period of a day starts at the on time of that day's window and ends at the off time, on the next day if the
 * off time is not after the on time. Equal times cover the calendar day, 00:00 to 00:00. Periods that touch or overlap
 * are one period, so that no switching time lies between them. Local times are resolved with [ZonedDateTime.of]: a
 * time in the gap of a DST change moves to after the gap, one in the doubled hour takes the earlier offset.
 */
object DisplaySchedule {
    /**
     * Days around the moment that are looked at. Two days in a row with the same window always have an off time
     * between them, so no on period is longer than the five weekdays plus the edges of a weekend.
     */
    private const val SEARCH_DAYS = 8L

    fun periodAt(
        at: ZonedDateTime,
        settings: DisplayScheduleSettings,
    ): Period {
        if (wholeDay(settings.weekdays) && wholeDay(settings.weekend)) return Period(true, null, null)
        val instant = at.toInstant()
        val today = at.toLocalDate()
        val on = merge((-SEARCH_DAYS..SEARCH_DAYS).mapNotNull { onPeriod(today.plusDays(it), settings, at.zone) })
        var previousEnd: Instant? = null
        for ((start, end) in on) {
            if (instant < start) return Period(false, previousEnd, start)
            if (instant < end) return Period(true, start, end)
            previousEnd = end
        }
        // Not reached: the search covers more than a week, and every week has on periods.
        return Period(false, previousEnd, null)
    }

    /** The next time the schedule switches after [at], `null` if it never does. */
    fun nextChange(
        at: ZonedDateTime,
        settings: DisplayScheduleSettings,
    ): Instant? = periodAt(at, settings).end

    fun windowOf(
        date: LocalDate,
        settings: DisplayScheduleSettings,
    ): DisplayWindow =
        when (date.dayOfWeek) {
            DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> settings.weekend
            else -> settings.weekdays
        }

    private fun wholeDay(window: DisplayWindow) = window.on == window.off

    /** The on period that starts on [date], `null` if it is empty (both times in the same DST gap). */
    private fun onPeriod(
        date: LocalDate,
        settings: DisplayScheduleSettings,
        zone: ZoneId,
    ): Pair<Instant, Instant>? {
        val window = windowOf(date, settings)
        val on = LocalTime.parse(window.on)
        val off = LocalTime.parse(window.off)
        val start: Instant
        val end: Instant
        when {
            on == off -> {
                start = date.atStartOfDay(zone).toInstant()
                end = date.plusDays(1).atStartOfDay(zone).toInstant()
            }

            off > on -> {
                start = ZonedDateTime.of(date, on, zone).toInstant()
                end = ZonedDateTime.of(date, off, zone).toInstant()
            }

            else -> {
                start = ZonedDateTime.of(date, on, zone).toInstant()
                end = ZonedDateTime.of(date.plusDays(1), off, zone).toInstant()
            }
        }
        return if (end > start) start to end else null
    }

    private fun merge(periods: List<Pair<Instant, Instant>>): List<Pair<Instant, Instant>> {
        val merged = ArrayList<Pair<Instant, Instant>>()
        for (p in periods.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && p.first <= last.second) {
                merged[merged.size - 1] = last.first to maxOf(last.second, p.second)
            } else {
                merged += p
            }
        }
        return merged
    }
}
