package io.github.tycho991.openframe.android

import android.graphics.Color
import io.github.tycho991.openframe.core.qr.QrMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Spec `slideshow-display`: Display of the pairing QR code. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class QrBitmapTest {
    private val matrix = QrMatrix.encode("http://192.168.100.215:8080/#pair=AbCdEfGhIjKlMnOpQrSt_-")

    @Test
    fun `Frameo gets at least 40 percent of the short side with whole pixels per module`() {
        val minSide = QrBitmap.minSide(1024, 600)
        assertEquals(240, minSide)
        val bitmap = QrBitmap.render(matrix, minSide)
        val modules = matrix.size + 2 * QrMatrix.QUIET_ZONE
        assertTrue(bitmap.width >= minSide)
        assertEquals(bitmap.width, bitmap.height)
        assertEquals(0, bitmap.width % modules)
    }

    @Test
    fun `Black on white with a quiet zone`() {
        val bitmap = QrBitmap.render(matrix, 240)
        val scale = bitmap.width / (matrix.size + 2 * QrMatrix.QUIET_ZONE)
        val quiet = QrMatrix.QUIET_ZONE * scale
        // The quiet zone is white all around.
        for (i in 0 until bitmap.width) {
            for (d in 0 until quiet) {
                assertEquals(Color.WHITE, bitmap.getPixel(i, d))
                assertEquals(Color.WHITE, bitmap.getPixel(d, i))
                assertEquals(Color.WHITE, bitmap.getPixel(i, bitmap.height - 1 - d))
                assertEquals(Color.WHITE, bitmap.getPixel(bitmap.width - 1 - d, i))
            }
        }
        // Every module is drawn as a block of one color.
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                val expected = if (matrix[x, y]) Color.BLACK else Color.WHITE
                assertEquals(expected, bitmap.getPixel(quiet + x * scale, quiet + y * scale))
                assertEquals(expected, bitmap.getPixel(quiet + x * scale + scale - 1, quiet + y * scale + scale - 1))
            }
        }
    }
}
