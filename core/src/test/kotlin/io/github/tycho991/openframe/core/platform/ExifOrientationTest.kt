package io.github.tycho991.openframe.core.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.max
import kotlin.math.min

/** The region of a section in the stored image, for engines that decode only that region (photo-crop, design decision 5). */
class ExifOrientationTest {
    /** Where a point of the stored image appears after the orientation's transform, as `AndroidMediaEngine.orientationMatrix` draws it. */
    private fun forward(
        orientation: Int,
        x: Double,
        y: Double,
    ): Pair<Double, Double> =
        when (orientation) {
            2 -> 1 - x to y
            3 -> 1 - x to 1 - y
            4 -> x to 1 - y
            5 -> y to x
            6 -> 1 - y to x
            7 -> 1 - y to 1 - x
            8 -> y to 1 - x
            else -> x to y
        }

    private fun shown(
        orientation: Int,
        stored: Region,
    ): Region {
        val (x1, y1) = forward(orientation, stored.x, stored.y)
        val (x2, y2) = forward(orientation, stored.x + stored.width, stored.y + stored.height)
        return Region(min(x1, x2), min(y1, y2), max(x1, x2) - min(x1, x2), max(y1, y2) - min(y1, y2))
    }

    private fun assertRegion(
        expected: Region,
        actual: Region,
        message: String,
    ) {
        assertEquals(expected.x, actual.x, 1e-12, "x, $message")
        assertEquals(expected.y, actual.y, 1e-12, "y, $message")
        assertEquals(expected.width, actual.width, 1e-12, "width, $message")
        assertEquals(expected.height, actual.height, 1e-12, "height, $message")
    }

    @Test
    fun `All eight orientations map an asymmetric region back to the stored image`() {
        // Asymmetric in position and size, so a swapped or mirrored axis cannot go unnoticed.
        val stored = Region(0.1, 0.2, 0.3, 0.5)
        for (orientation in 1..8) {
            assertRegion(stored, ExifOrientation.toStored(orientation, shown(orientation, stored)), "orientation $orientation")
        }
    }

    @Test
    fun `Orientation 6 by hand`() {
        // A camera held upright stores the image lying on its left side. The top left quarter of the upright photo
        // is the bottom left quarter of the stored one.
        assertRegion(Region(0.0, 0.5, 0.5, 0.5), ExifOrientation.toStored(6, Region(0.0, 0.0, 0.5, 0.5)), "top left")
        // A strip at the top of the upright photo is a strip on the left of the stored one.
        assertRegion(Region(0.0, 0.0, 0.2, 1.0), ExifOrientation.toStored(6, Region(0.0, 0.0, 1.0, 0.2)), "strip")
        assertEquals(true, ExifOrientation.swapsAxes(6))
    }

    @Test
    fun `The whole image stays whole, an unknown orientation counts as normal`() {
        for (orientation in 0..9) assertRegion(Region.FULL, ExifOrientation.toStored(orientation, Region.FULL), "orientation $orientation")
        val region = Region(0.1, 0.2, 0.3, 0.4)
        assertRegion(region, ExifOrientation.toStored(0, region), "unknown")
    }
}
