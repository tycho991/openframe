package io.github.tycho991.openframe.android

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Trims a decoded photo to what the screen can show of it.
 *
 * The pre-rendered display size covers the screen, so a portrait photo on a landscape screen is far
 * taller than the screen. During a transition two photos are drawn at once, and the GPU texture
 * cache of the Lenovo Yoga Tablet 2 (24 MiB at 1920×1200) holds a 1920×2556 portrait (18.7 MB) and a
 * 1920×1440 landscape (10.5 MB) only one at a time: the textures are evicted and uploaded again every
 * frame, up to 100 ms per frame. A bitmap that is no bigger than the screen needs avoids that.
 */
object BitmapFit {
    /**
     * [src] reduced for a view of [viewW]×[viewH], which shows it with `CENTER_CROP` ([fill]) or
     * `FIT_CENTER`. The view looks the same afterwards. Returns [src] if there is nothing to trim,
     * otherwise a new bitmap, and recycles [src].
     */
    fun forView(
        src: Bitmap,
        viewW: Int,
        viewH: Int,
        fill: Boolean,
    ): Bitmap {
        if (viewW <= 0 || viewH <= 0) return src
        val w = src.width
        val h = src.height
        val trimmed =
            if (fill) {
                // CENTER_CROP shows a centred window of the view's aspect ratio.
                val scale = max(viewW.toFloat() / w, viewH.toFloat() / h)
                val windowW = min(w, (viewW / scale).roundToInt()).coerceAtLeast(1)
                val windowH = min(h, (viewH / scale).roundToInt()).coerceAtLeast(1)
                if (windowW >= w && windowH >= h) src else Bitmap.createBitmap(src, (w - windowW) / 2, (h - windowH) / 2, windowW, windowH)
            } else {
                val scale = min(viewW.toFloat() / w, viewH.toFloat() / h)
                if (scale >= 1f) {
                    src
                } else {
                    Bitmap.createScaledBitmap(
                        src,
                        (w * scale).roundToInt().coerceAtLeast(1),
                        (h * scale).roundToInt().coerceAtLeast(1),
                        true,
                    )
                }
            }
        if (trimmed !== src) src.recycle()
        return trimmed
    }
}
