package io.github.tycho991.openframe.android

import io.github.tycho991.openframe.core.settings.Corner
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec `slideshow-display`, requirement "Caption and clock in the same corner"; a plain JVM test. */
class CornerOffsetsTest {
    /** The clock's height at 50 %, 100 % and 300 %, roughly as on the Frameo. */
    private val heights = listOf(55, 110, 330)

    @Test
    fun `Both bottom right - the caption stands directly above the clock`() {
        for (h in heights) {
            for (corner in listOf(Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT)) {
                assertEquals(CornerOffsets(0, h + 8), cornerOffsets(corner, true, h, corner, gap = 8))
            }
        }
    }

    @Test
    fun `In the top corners the caption stands below the clock`() {
        for (h in heights) {
            for (corner in listOf(Corner.TOP_LEFT, Corner.TOP_RIGHT)) {
                assertEquals(CornerOffsets(h + 8, 0), cornerOffsets(corner, true, h, corner, gap = 8))
            }
        }
    }

    @Test
    fun `Clock switched off - the caption stands in the corner itself`() {
        for (corner in Corner.entries) {
            assertEquals(CornerOffsets(0, 0), cornerOffsets(corner, false, 330, corner, gap = 8))
        }
    }

    @Test
    fun `Different corners do not move the caption`() {
        for (clock in Corner.entries) {
            for (caption in Corner.entries.filter { it != clock }) {
                for (h in heights) assertEquals(CornerOffsets(0, 0), cornerOffsets(clock, true, h, caption, gap = 8))
            }
        }
    }

    @Test
    fun `A clock that has not been laid out yet moves nothing`() {
        assertEquals(CornerOffsets(0, 0), cornerOffsets(Corner.BOTTOM_LEFT, true, 0, Corner.BOTTOM_LEFT, gap = 8))
    }
}
