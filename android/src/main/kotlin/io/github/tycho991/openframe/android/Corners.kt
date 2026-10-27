package io.github.tycho991.openframe.android

import android.annotation.SuppressLint
import android.view.Gravity
import io.github.tycho991.openframe.core.settings.Corner

/**
 * The vertical and the horizontal gravity of [corner], for the clock and the caption alike. The corners are those of
 * the screen: left and right also in a right-to-left language.
 */
@SuppressLint("RtlHardcoded")
fun cornerGravity(corner: Corner): Pair<Int, Int> =
    when (corner) {
        Corner.TOP_LEFT -> Gravity.TOP to Gravity.LEFT
        Corner.TOP_RIGHT -> Gravity.TOP to Gravity.RIGHT
        Corner.BOTTOM_LEFT -> Gravity.BOTTOM to Gravity.LEFT
        Corner.BOTTOM_RIGHT -> Gravity.BOTTOM to Gravity.RIGHT
    }

/** Extra margins of the caption at the top and the bottom, in pixels. */
data class CornerOffsets(
    val top: Int,
    val bottom: Int,
)

/**
 * Where the caption stands next to a switched-on clock in its corner (spec "Caption and clock in the same corner"):
 * the clock stays in the corner, the caption moves towards the middle by the clock's height and [gap], above it in
 * the bottom corners and below it in the top corners. Elsewhere, or with the clock off, it stands in the corner.
 */
fun cornerOffsets(
    clockCorner: Corner,
    clockOn: Boolean,
    clockHeight: Int,
    captionCorner: Corner,
    gap: Int = 0,
): CornerOffsets {
    if (!clockOn || clockCorner != captionCorner || clockHeight <= 0) return CornerOffsets(0, 0)
    val shift = clockHeight + gap
    return when (captionCorner) {
        Corner.TOP_LEFT, Corner.TOP_RIGHT -> CornerOffsets(shift, 0)
        Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT -> CornerOffsets(0, shift)
    }
}
