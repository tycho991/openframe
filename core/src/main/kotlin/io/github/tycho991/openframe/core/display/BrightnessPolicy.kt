package io.github.tycho991.openframe.core.display

import io.github.tycho991.openframe.core.platform.RawBrightness
import io.github.tycho991.openframe.core.settings.BrightnessMode
import io.github.tycho991.openframe.core.settings.BrightnessSettings
import io.github.tycho991.openframe.core.settings.DisplayScheduleSettings
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** What the frame sets Android's brightness to. */
sealed interface BrightnessTarget {
    data class Level(
        val level: Int,
    ) : BrightnessTarget

    data object Auto : BrightnessTarget

    /** Back to what Android had when dimming began in mode `system`. */
    data class Restore(
        val raw: RawBrightness,
    ) : BrightnessTarget
}

/**
 * A brightness a client set during a dim period or the night after one (spec `display-brightness` › Brightness now).
 * It holds while the dim stretch that starts at [start] lasts; [id] tells two requests apart, so that each one is
 * applied even with the same level.
 */
data class BrightnessOverride(
    val level: Int,
    val start: Instant,
    val id: Long,
)

/** What the brightness decision depends on, collected by [DisplayController]. */
data class BrightnessInputs(
    val brightness: BrightnessSettings,
    val schedule: DisplayScheduleSettings,
    /** openframe may modify system settings. */
    val available: Boolean,
    val override: BrightnessOverride?,
    /** What Android had when dimming began in mode `system`, `null` without one. */
    val snapshot: RawBrightness?,
)

/**
 * The desired brightness: [target], or `null` to leave Android's brightness alone. [dimStart] is the start of the
 * current dim stretch (a dim period, or the night after one), `null` outside one. [edge] identifies why: the frame
 * writes only when it changes, so that a change on the device holds until the next one (design decision 4).
 */
data class BrightnessDecision(
    val target: BrightnessTarget?,
    val dimStart: Instant?,
    val edge: Any?,
) {
    /** The schedule dims at the moment. */
    val dimmed: Boolean get() = dimStart != null
}

/** The desired brightness (design decision 4). Pure, so that it is tested without time passing. */
object BrightnessPolicy {
    private data class DimEdge(
        val start: Instant,
        val level: Int,
    )

    private data class OverrideEdge(
        val id: Long,
    )

    private data class StoredEdge(
        val brightness: BrightnessSettings,
    )

    fun decide(
        inputs: BrightnessInputs,
        now: Instant,
        zone: ZoneId,
    ): BrightnessDecision {
        if (!inputs.available) return BrightnessDecision(null, null, null)
        val dimStart = dimStart(inputs.schedule, now, zone)
        if (dimStart != null) {
            val override = inputs.override
            if (override != null && override.start == dimStart) {
                return BrightnessDecision(BrightnessTarget.Level(override.level), dimStart, OverrideEdge(override.id))
            }
            val level = inputs.schedule.dimLevel
            return BrightnessDecision(BrightnessTarget.Level(level), dimStart, DimEdge(dimStart, level))
        }
        val stored = inputs.brightness
        val target =
            when (stored.mode) {
                BrightnessMode.MANUAL -> BrightnessTarget.Level(stored.level)
                BrightnessMode.AUTO -> BrightnessTarget.Auto
                BrightnessMode.SYSTEM -> inputs.snapshot?.let { BrightnessTarget.Restore(it) }
            }
        return BrightnessDecision(target, null, StoredEdge(stored))
    }

    /**
     * The start of the dim stretch around [now]: a dim period, or the night after one, which keeps the dim level for a
     * display woken or switched on by hand (spec `display-schedule` › Dimmed during the night). A clock that is not set
     * yet counts as no schedule, as for the display.
     */
    fun dimStart(
        schedule: DisplayScheduleSettings,
        now: Instant,
        zone: ZoneId,
    ): Instant? {
        if (!schedule.enabled || !DisplayPolicy.clockSet(now)) return null
        val at = ZonedDateTime.ofInstant(now, zone)
        return DisplaySchedule.dimAt(at, schedule)?.start ?: DisplaySchedule.nightAfterDim(at, schedule)?.start
    }
}
