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

@Serializable
enum class ClockPosition {
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
    val position: ClockPosition = ClockPosition.BOTTOM_RIGHT,
    val sizePercent: Int = 100,
    val format: ClockFormat = ClockFormat.H24,
)

/** On and off time of a day's window, each as `HH:MM` in the device's local time. */
@Serializable
data class DisplayWindow(
    val on: String,
    val off: String,
) {
    companion object {
        val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    }
}

/** The display schedule (spec `display-schedule`), apart from the arithmetic in `display.DisplaySchedule`. */
@Serializable
data class DisplayScheduleSettings(
    val enabled: Boolean = false,
    val weekdays: DisplayWindow = DisplayWindow("07:00", "22:00"),
    val weekend: DisplayWindow = DisplayWindow("08:00", "23:00"),
    val wakeMinutes: Int = 5,
) {
    companion object {
        val WAKE_MINUTES = 1..120
    }
}

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
    val language: FrameLanguage = FrameLanguage.AUTO,
    val cacheBudgetMb: Int = 1000,
    val displaySchedule: DisplayScheduleSettings = DisplayScheduleSettings(),
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
