package io.github.tycho991.openframe.core.display

import io.github.tycho991.openframe.core.settings.DisplayScheduleSettings
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Why the display is in its state, as `GET /display` reports it. */
enum class DisplayCause(
    val api: String,
) {
    SCHEDULE("schedule"),
    MANUAL("manual"),
    WAKE("wake"),
    NONE("none"),
}

/**
 * The manual switch: the chosen value and the period of the schedule it was set in, `null` without an active schedule.
 * It holds as long as that period lasts.
 */
data class Override(
    val on: Boolean,
    val period: Period?,
)

/** What the decision depends on, collected by [DisplayController]. */
data class DisplayInputs(
    val schedule: DisplayScheduleSettings,
    /** openframe is a device administrator. */
    val available: Boolean,
    val override: Override?,
    /** Monotonic time until which a woken display stays on during an off period. */
    val wakeUntil: Long?,
)

/**
 * The desired state: [on], or `null` when the frame decides nothing. [period] is the schedule's current period, `null`
 * without an active schedule.
 */
data class Decision(
    val on: Boolean?,
    val cause: DisplayCause,
    val nextChange: Instant?,
    val period: Period?,
)

/**
 * The desired state of the display (design decision 4). Pure, so that it is tested without time passing.
 *
 * `nextChange` is when the display changes next by itself: the end of the period or of waking, or, where the state
 * that follows is the same (a manual off by day runs into the night), the end of the period after it.
 */
object DisplayPolicy {
    fun decide(
        inputs: DisplayInputs,
        now: Instant,
        zone: ZoneId,
        monotonicMillis: Long,
    ): Decision {
        if (!inputs.available) return Decision(null, DisplayCause.NONE, null, null)
        val period = if (inputs.schedule.enabled) DisplaySchedule.periodAt(ZonedDateTime.ofInstant(now, zone), inputs.schedule) else null
        val override = inputs.override
        if (override != null && (period == null || samePeriod(override.period, period))) {
            val next = period?.let { if (override.on == it.on) it.end else endOfNext(it, inputs, zone) }
            return Decision(override.on, DisplayCause.MANUAL, next, period)
        }
        if (period == null) return Decision(null, DisplayCause.NONE, null, null)
        if (period.on) return Decision(true, DisplayCause.SCHEDULE, period.end, period)
        val wakeUntil = inputs.wakeUntil
        if (wakeUntil != null && wakeUntil > monotonicMillis) {
            val wakeEnd = now.plusMillis(wakeUntil - monotonicMillis)
            val end = period.end
            val next = if (end == null || wakeEnd < end) wakeEnd else endOfNext(period, inputs, zone)
            return Decision(true, DisplayCause.WAKE, next, period)
        }
        return Decision(false, DisplayCause.SCHEDULE, period.end, period)
    }

    /** The end of the period that follows [period]. */
    private fun endOfNext(
        period: Period,
        inputs: DisplayInputs,
        zone: ZoneId,
    ): Instant? = period.end?.let { DisplaySchedule.periodAt(ZonedDateTime.ofInstant(it, zone), inputs.schedule).end }

    /** Whether two periods are the same one. A change of the schedule that moves its start makes it another. */
    fun samePeriod(
        a: Period?,
        b: Period?,
    ): Boolean = a?.on == b?.on && a?.start == b?.start
}
