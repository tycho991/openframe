package io.github.tycho991.openframe.core.platform

import kotlin.math.max
import kotlin.math.min

/** A rectangle of an image as fractions of its width and height, top left corner [x], [y]. */
data class Region(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    val isFull: Boolean get() = this == FULL

    companion object {
        val FULL = Region(0.0, 0.0, 1.0, 1.0)
    }
}

/** EXIF orientations 1 to 8 as pure arithmetic, for engines that decode the stored, unrotated image. */
object ExifOrientation {
    /** Whether the orientation swaps width and height (5 to 8). */
    fun swapsAxes(orientation: Int) = orientation in 5..8

    /**
     * [region] of the EXIF-rotated image as the region of the stored image that shows the same pixels.
     * An unknown orientation counts as 1, as the engines treat it.
     */
    fun toStored(
        orientation: Int,
        region: Region,
    ): Region {
        val (x1, y1) = pointToStored(orientation, region.x, region.y)
        val (x2, y2) = pointToStored(orientation, region.x + region.width, region.y + region.height)
        return Region(min(x1, x2), min(y1, y2), max(x1, x2) - min(x1, x2), max(y1, y2) - min(y1, y2))
    }

    /** The point [u], [v] of the rotated image in the stored one, the inverse of the orientation's transform. */
    private fun pointToStored(
        orientation: Int,
        u: Double,
        v: Double,
    ): Pair<Double, Double> =
        when (orientation) {
            // Mirrored horizontally.
            2 -> 1 - u to v

            // Rotated by 180°.
            3 -> 1 - u to 1 - v

            // Mirrored vertically.
            4 -> u to 1 - v

            // Transposed: mirrored at the diagonal from top left to bottom right.
            5 -> v to u

            // Rotated by 90° clockwise for display.
            6 -> v to 1 - u

            // Transversed: mirrored at the other diagonal.
            7 -> 1 - v to 1 - u

            // Rotated by 90° counterclockwise for display.
            8 -> 1 - v to u

            else -> u to v
        }
}
