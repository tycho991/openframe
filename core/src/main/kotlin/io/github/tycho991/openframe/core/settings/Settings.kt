package io.github.tycho991.openframe.core.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
}

@Serializable
data class ClockSettings(
    val enabled: Boolean = true,
    val position: ClockPosition = ClockPosition.BOTTOM_RIGHT,
    val sizePercent: Int = 100,
    val format: ClockFormat = ClockFormat.H24,
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
    val fillMode: FillMode = FillMode.FILL,
    val orientation: Orientation = Orientation.LANDSCAPE,
    val clock: ClockSettings = ClockSettings(),
    val language: FrameLanguage = FrameLanguage.AUTO,
    val cacheBudgetMb: Int = 1000,
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
