package io.github.tycho991.openframe.android

import android.graphics.Bitmap
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
    fun `A display size of the screen's size is left alone`() {
        val src = bitmap(1920, 1200)
        assertSame(src, BitmapFit.forView(src, 1920, 1200))
    }

    @Test
    fun `A display size with borders is left alone`() {
        // A portrait photo in `auto` on a landscape screen: 1200×1200, the borders come from FIT_CENTER.
        val src = bitmap(1200, 1200)
        assertSame(src, BitmapFit.forView(src, 1920, 1200))
    }

    @Test
    fun `A bitmap smaller than the view is not enlarged`() {
        val src = bitmap(800, 600)
        assertSame(src, BitmapFit.forView(src, 1920, 1200))
    }

    @Test
    fun `A bitmap of the other orientation is scaled down to the view`() {
        // Rendered for landscape, shown on portrait until the new size is rendered.
        val src = bitmap(1920, 1200)
        val result = BitmapFit.forView(src, 1200, 1920)
        assertEquals(1200 to 750, result.width to result.height)
        assertTrue(src.isRecycled)
    }

    @Test
    fun `Unknown view size leaves the bitmap alone`() {
        val src = bitmap(1920, 2556)
        assertSame(src, BitmapFit.forView(src, 0, 0))
    }
}
