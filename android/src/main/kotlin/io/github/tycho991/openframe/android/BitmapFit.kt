package io.github.tycho991.openframe.android

import android.graphics.Bitmap
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Scales a decoded photo down to what the screen can show of it.
 *
 * The core renders the display size at most at screen size, as the part of the photo the frame shows. A bitmap can
 * still be larger than the view, for example after switching the frame's orientation until the new size is rendered.
 * During a transition two photos are drawn at once, and the GPU texture cache of the Lenovo Yoga Tablet 2 (24 MiB at
 * 1920×1200) holds two oversized bitmaps only one at a time: the textures are evicted and uploaded again every frame,
 * up to 100 ms per frame. A bitmap that is no bigger than the screen needs avoids that.
 */
object BitmapFit {
    /**
     * [src] scaled down for a view of [viewW]×[viewH], which shows it with `FIT_CENTER`. The view looks the same
     * afterwards. Returns [src] if it fits, otherwise a new bitmap, and recycles [src].
     */
    fun forView(
        src: Bitmap,
        viewW: Int,
        viewH: Int,
    ): Bitmap {
        if (viewW <= 0 || viewH <= 0) return src
        val scale = min(viewW.toFloat() / src.width, viewH.toFloat() / src.height)
        if (scale >= 1f) return src
        val scaled =
            Bitmap.createScaledBitmap(
                src,
                (src.width * scale).roundToInt().coerceAtLeast(1),
                (src.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        if (scaled !== src) src.recycle()
        return scaled
    }
}
