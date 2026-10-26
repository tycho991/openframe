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

/**
 * Spec `slideshow-display`: Smooth display on weak hardware (bitmaps no bigger than the screen needs), Fill mode (black
 * borders), Transitions (a crossfade blends the borders too).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class BitmapFitTest {
    private fun bitmap(
        w: Int,
        h: Int,
        color: Int = Color.BLUE,
    ) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun `A display size of the screen's size is left alone`() {
        val src = bitmap(1920, 1200)
        assertSame(src, BitmapFit.forView(src, 1920, 1200))
    }

    @Test
    fun `A display size with borders gets them as opaque black pixels`() {
        // A portrait photo in `auto` on a landscape screen: 1200×1200, borders of 360 on each side.
        val src = bitmap(1200, 1200)
        val result = BitmapFit.forView(src, 1920, 1200)
        assertEquals(1920 to 1200, result.width to result.height)
        assertEquals(Color.BLACK, result.getPixel(100, 600))
        assertEquals(Color.BLACK, result.getPixel(1819, 600))
        assertEquals(Color.BLUE, result.getPixel(960, 600))
        assertEquals(Color.BLUE, result.getPixel(361, 0))
        assertTrue(src.isRecycled)
    }

    @Test
    fun `A panorama gets borders at the top and bottom`() {
        val result = BitmapFit.forView(bitmap(1920, 853), 1920, 1200)
        assertEquals(1920 to 1200, result.width to result.height)
        assertEquals(Color.BLACK, result.getPixel(960, 50))
        assertEquals(Color.BLUE, result.getPixel(960, 600))
    }

    @Test
    fun `A bitmap smaller than the view is not enlarged, only padded to its aspect ratio`() {
        val src = bitmap(800, 500)
        // 800×500 has the aspect ratio of 1920×1200 already.
        assertSame(src, BitmapFit.forView(src, 1920, 1200))
        val result = BitmapFit.forView(bitmap(600, 600), 1920, 1200)
        assertEquals(960 to 600, result.width to result.height)
    }

    @Test
    fun `A bitmap of the other orientation is scaled down to the view`() {
        // Rendered for landscape, shown on portrait until the new size is rendered: 1200×750, padded to 1200×1920.
        val src = bitmap(1920, 1200)
        val result = BitmapFit.forView(src, 1200, 1920)
        assertEquals(1200 to 1920, result.width to result.height)
        assertEquals(Color.BLACK, result.getPixel(600, 100))
        assertEquals(Color.BLUE, result.getPixel(600, 960))
        assertTrue(src.isRecycled)
    }

    @Test
    fun `A side within a pixel of the aspect ratio is left alone`() {
        assertEquals(1920 to 1199, BitmapFit.paddedSize(1920, 1199, 1920, 1200))
        assertEquals(1200 to 1200, BitmapFit.paddedSize(1200, 1200, 1200, 1199))
        assertEquals(1920 to 1200, BitmapFit.paddedSize(1200, 1200, 1920, 1200))
    }

    @Test
    fun `Unknown view size leaves the bitmap alone`() {
        val src = bitmap(1920, 2556)
        assertSame(src, BitmapFit.forView(src, 0, 0))
    }
}
