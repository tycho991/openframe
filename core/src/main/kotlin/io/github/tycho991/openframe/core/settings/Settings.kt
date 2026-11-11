package io.github.tycho991.openframe.core.settings

import io.github.tycho991.openframe.core.platform.PlatformInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
enum class Order {
    @SerialName("shuffle")
    SHUFFLE,

    @SerialName("chronological")
    CHRONOLOGICAL,
}

@Serializable
enum class Transition {
    @SerialName("fade")
    FADE,

    @SerialName("slide")
    SLIDE,

    @SerialName("none")
    NONE,
}

@Serializable
enum class FillMode {
    /** Crops at most [io.github.tycho991.openframe.core.library.Presentation.AUTO_MAX_CROP], the rest becomes borders. */
    @SerialName("auto")
    AUTO,

    @SerialName("fill")
    FILL,

    @SerialName("fit")
    FIT,
}

@Serializable
enum class Orientation {
    @SerialName("landscape")
    LANDSCAPE,

    @SerialName("portrait")
    PORTRAIT,
}

/** A corner of the screen, for the clock and for the caption. */
@Serializable
enum class Corner {
    @SerialName("top-left")
    TOP_LEFT,

    @SerialName("top-right")
    TOP_RIGHT,

    @SerialName("bottom-left")
    BOTTOM_LEFT,

    @SerialName("bottom-right")
    BOTTOM_RIGHT,
}

@Serializable
enum class ClockFormat {
    @SerialName("24h")
    H24,

    @SerialName("12h")
    H12,
}

@Serializable
enum class FrameLanguage {
    @SerialName("auto")
    AUTO,

    @SerialName("de")
    DE,

    @SerialName("en")
    EN,
    ;

    /**
     * The locale of the frame's texts, its clock's date and its place names: `auto` follows the system, otherwise the
     * chosen language applies.
     */
    fun locale(platform: PlatformInfo): Locale =
        when (this) {
            AUTO -> platform.systemLocale
            DE -> Locale.GERMAN
            EN -> Locale.ENGLISH
        }
}

@Serializable
data class ClockSettings(
    val enabled: Boolean = true,
    val position: Corner = Corner.BOTTOM_RIGHT,
    val sizePercent: Int = 100,
    val format: ClockFormat = ClockFormat.H24,
)

/**
 * The caption over the photo (spec `slideshow-display`, "Caption over the photo"): it always shows the comment; the
 * album title and the place are switched on here. It may share its corner with the clock.
 */
@Serializable
data class CaptionSettings(
    val albumTitle: Boolean = false,
    val place: Boolean = false,
    val position: Corner = Corner.BOTTOM_LEFT,
)

/**
 * On and off time of a day's window, each as `HH:MM` in the device's local time, and the time from which the schedule
 * dims, `null` for none (spec `display-schedule` › Dim settings).
 */
@Serializable
data class DisplayWindow(
    val on: String,
    val off: String,
    val dim: String? = null,
) {
    /** The dim time lies after the on time and before the off time along the period; with equal times, not at the on time. */
    fun dimFits(): Boolean {
        val d = dim ?: return true
        val on = minutes(on)
        val off = minutes(off)
        val dim = minutes(d)
        return when {
            on < off -> dim in (on + 1) until off
            off < on -> dim > on || dim < off
            else -> dim != on
        }
    }

    companion object {
        val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

        private fun minutes(time: String): Int = time.substring(0, 2).toInt() * 60 + time.substring(3, 5).toInt()
    }
}

/** The display schedule (spec `display-schedule`), apart from the arithmetic in `display.DisplaySchedule`. */
@Serializable
data class DisplayScheduleSettings(
    val enabled: Boolean = false,
    val weekdays: DisplayWindow = DisplayWindow("07:00", "22:00"),
    val weekend: DisplayWindow = DisplayWindow("08:00", "23:00"),
    val wakeMinutes: Int = 5,
    /** Brightness in percent from a window's dim time. */
    val dimLevel: Int = 20,
) {
    companion object {
        val WAKE_MINUTES = 1..120
        val DIM_LEVEL = 1..100
    }
}

/** How the frame sets the brightness (spec `display-brightness` › Modes of the brightness). */
@Serializable
enum class BrightnessMode {
    /** Android's brightness is left alone, except while the schedule dims. */
    @SerialName("system")
    SYSTEM,

    @SerialName("manual")
    MANUAL,

    /** Android's automatic brightness, only with a light sensor. */
    @SerialName("auto")
    AUTO,
}

@Serializable
data class BrightnessSettings(
    val mode: BrightnessMode = BrightnessMode.SYSTEM,
    /** Brightness in percent for [BrightnessMode.MANUAL]. */
    val level: Int = 80,
) {
    companion object {
        val LEVEL = 1..100
    }
}

/** What the device allows for the brightness, checked when a client changes the mode. */
data class BrightnessSupport(
    /** openframe may modify system settings. */
    val available: Boolean,
    /** The device has a light sensor. */
    val autoSupported: Boolean,
)

/**
 * The frame's settings as the API delivers them. The default values are
 * documented in spec `frame-settings`. The "local only" mode is not part
 * of them, see [SettingsRepository.localOnly].
 */
@Serializable
data class Settings(
    val name: String,
    val intervalSeconds: Int = 30,
    val order: Order = Order.SHUFFLE,
    val transition: Transition = Transition.FADE,
    val transitionDurationMs: Int = 1500,
    val fillMode: FillMode = FillMode.AUTO,
    val orientation: Orientation = Orientation.LANDSCAPE,
    val clock: ClockSettings = ClockSettings(),
    val caption: CaptionSettings = CaptionSettings(),
    val language: FrameLanguage = FrameLanguage.AUTO,
    val cacheBudgetMb: Int = 1000,
    val displaySchedule: DisplayScheduleSettings = DisplayScheduleSettings(),
    /** The frame checks GitHub for a new version by itself (spec `app-update`, "Automatic check"). */
    val autoUpdateCheck: Boolean = true,
    val brightness: BrightnessSettings = BrightnessSettings(),
) {
    companion object {
        const val NAME_MAX_LENGTH = 64
        val INTERVAL_SECONDS = 5..86_400
        val TRANSITION_DURATION_MS = 0..5000
        val CLOCK_SIZE_PERCENT = 50..300
        val CACHE_BUDGET_MB = 100..10_000

        /** Default values. The name is the device model. */
        fun defaults(model: String) = Settings(name = model.take(NAME_MAX_LENGTH).ifBlank { "openframe" })
    }
}
