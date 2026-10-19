package io.github.tycho991.openframe.android

import android.graphics.Bitmap
import android.graphics.Color
import io.github.tycho991.openframe.core.qr.QrMatrix

/** Draws a [QrMatrix] for the frame: black on white with quiet zone, whole pixels per module. */
object QrBitmap {
    /** Share of the short screen side that the QR code covers at least, quiet zone included. */
    const val MIN_SHARE = 0.4

    /**
     * Renders [matrix] with a quiet zone of [QrMatrix.QUIET_ZONE] modules. Each module gets the
     * same whole number of pixels, so that the edge is at least [minSide] pixels; no smoothing.
     */
    fun render(
        matrix: QrMatrix,
        minSide: Int,
    ): Bitmap {
        val modules = matrix.size + 2 * QrMatrix.QUIET_ZONE
        val scale = maxOf(1, (minSide + modules - 1) / modules)
        val side = modules * scale
        val pixels =
            IntArray(side * side) { i ->
                val x = i % side / scale - QrMatrix.QUIET_ZONE
                val y = i / side / scale - QrMatrix.QUIET_ZONE
                if (x in 0 until matrix.size && y in 0 until matrix.size && matrix[x, y]) Color.BLACK else Color.WHITE
            }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    }

    /** The minimum edge for a screen of [width] × [height] pixels. */
    fun minSide(
        width: Int,
        height: Int,
    ): Int = Math.ceil(minOf(width, height) * MIN_SHARE).toInt()
}
