package io.github.tycho991.openframe.android

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Spec `slideshow-display`: Smooth display on weak hardware (bitmaps no bigger than the screen needs). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class BitmapFitTest {
    private fun bitmap(
        w: Int,
        h: Int,
    ) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    @Test
    fun `Portrait on a landscape screen is cut to the visible window in fill mode`() {
        val src = bitmap(1920, 2556)
        // Marks: red in the vertical middle, blue near the top (outside the window).
        src.setPixel(960, 1278, Color.RED)
        src.setPixel(960, 100, Color.BLUE)
        val result = BitmapFit.forView(src, 1920, 1200, fill = true)
        assertEquals(1920, result.width)
        assertEquals(1200, result.height)
        assertEquals(Color.RED, result.getPixel(960, 1278 - (2556 - 1200) / 2))
        assertTrue(src.isRecycled)
    }

    @Test
    fun `A landscape photo with a taller aspect ratio is cut at top and bottom`() {
        val result = BitmapFit.forView(bitmap(1920, 1440), 1920, 1200, fill = true)
        assertEquals(1920 to 1200, result.width to result.height)
    }

    @Test
    fun `A bitmap smaller than the view is only trimmed to the visible window`() {
        val src = bitmap(1152, 864)
        // Fit shows all of it, so nothing is trimmed.
        assertSame(src, BitmapFit.forView(src, 1920, 1200, fill = false))
        // Fill shows a 1152×720 window of it, scaled up by the GPU.
        val result = BitmapFit.forView(src, 1920, 1200, fill = true)
        assertEquals(1152 to 720, result.width to result.height)
    }

    @Test
    fun `Portrait in fit mode is scaled down to the screen`() {
        val result = BitmapFit.forView(bitmap(1920, 2556), 1920, 1200, fill = false)
        assertEquals(1200, result.height)
        assertTrue(result.width in 900..902)
    }

    @Test
    fun `The aspect ratio of the window matches the view`() {
        val result = BitmapFit.forView(bitmap(2047, 1200), 1920, 1200, fill = true)
        assertEquals(1920 to 1200, result.width to result.height)
    }

    @Test
    fun `Unknown view size leaves the bitmap alone`() {
        val src = bitmap(1920, 2556)
        assertSame(src, BitmapFit.forView(src, 0, 0, fill = true))
    }
}
