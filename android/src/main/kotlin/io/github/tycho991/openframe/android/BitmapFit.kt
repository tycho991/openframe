package io.github.tycho991.openframe.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Prepares a decoded photo for a view that shows it with `FIT_CENTER`: scaled down to what the screen can show, and
 * padded with black to the view's aspect ratio.
 *
 * The core renders the display size at most at screen size, as the part of the photo the frame shows. A bitmap can
 * still be larger than the view, for example after switching the frame's orientation until the new size is rendered.
 * During a transition two photos are drawn at once, and the GPU texture cache of the Lenovo Yoga Tablet 2 (24 MiB at
 * 1920×1200) holds two oversized bitmaps only one at a time: the textures are evicted and uploaded again every frame,
 * up to 100 ms per frame. A bitmap that is no bigger than the screen needs avoids that.
 *
 * The black borders are part of the bitmap, not transparent space around it: a crossfade then blends the borders of
 * the incoming photo over the outgoing one like the rest of it, instead of leaving the old photo in them until the
 * transition ends (`FINDINGS.md` 57). The views keep having no background, so `alpha` needs no hardware layer.
 */
object BitmapFit {
    /**
     * [src] scaled down for a view of [viewW]×[viewH] and padded with black to its aspect ratio, centred. The view
     * looks the same afterwards. Returns [src] if there is nothing to do, otherwise a new bitmap, and recycles [src].
     */
    fun forView(
        src: Bitmap,
        viewW: Int,
        viewH: Int,
    ): Bitmap {
        if (viewW <= 0 || viewH <= 0) return src
        val scale = min(1f, min(viewW.toFloat() / src.width, viewH.toFloat() / src.height))
        val w = (src.width * scale).roundToInt().coerceAtLeast(1)
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)
        val (boxW, boxH) = paddedSize(w, h, viewW, viewH)
        if (scale >= 1f && boxW == w && boxH == h) return src
        val out = Bitmap.createBitmap(boxW, boxH, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.BLACK)
        val left = (boxW - w) / 2f
        val top = (boxH - h) / 2f
        Canvas(out).drawBitmap(
            src,
            null,
            RectF(left, top, left + w, top + h),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        src.recycle()
        return out
    }

    /**
     * The smallest size of the view's aspect ratio around [w]×[h]. A side within one pixel of the exact ratio is left
     * alone: `FIT_CENTER` fills the view with it anyway, and a one-pixel band would only cost a copy.
     */
    fun paddedSize(
        w: Int,
        h: Int,
        viewW: Int,
        viewH: Int,
    ): Pair<Int, Int> {
        val wantW = (h.toDouble() * viewW / viewH).roundToInt()
        val wantH = (w.toDouble() * viewH / viewW).roundToInt()
        return when {
            wantW > w + 1 -> wantW to h
            wantH > h + 1 -> w to wantH
            else -> w to h
        }
    }
}
