package io.github.tycho991.openframe.core.display

import kotlin.math.roundToInt

/**
 * Levels in percent on Android's brightness scale of 0 to [MAX] (spec `display-brightness` › Level scale): linear,
 * with 1 % at the lowest value above zero, so that no level switches the backlight off.
 */
object BrightnessScale {
    const val MAX = 255

    fun toRaw(level: Int): Int = maxOf(1, (level.coerceIn(1, 100) * MAX / 100.0).roundToInt())

    fun toLevel(raw: Int): Int = (raw.coerceIn(0, MAX) * 100.0 / MAX).roundToInt()
}
