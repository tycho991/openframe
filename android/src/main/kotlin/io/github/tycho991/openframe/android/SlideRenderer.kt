package io.github.tycho991.openframe.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.settings.FillMode
import io.github.tycho991.openframe.core.settings.Transition
import java.io.File
import java.util.concurrent.Executors

/**
 * Shows photos with two stacked `ImageView`s. Decoding happens on a
 * thread of its own, only the pre-rendered display size, without further
 * scaling. There are at most two bitmaps: the current and the next.
 * Transitions run through `ViewPropertyAnimator`. Deliberately without `withLayer()`: an `ImageView` without a
 * background does not overlap itself, so `alpha` and `translationX` need no
 * hardware layer. The layer cost about every fifth vsync on the Frameo (measured: 22 % missed
 * with it, 2 to 4 % without).
 */
class SlideRenderer(
    private var front: ImageView,
    private var back: ImageView,
    /** A photo could not be decoded. */
    private val onUndecodable: (PhotoEntry) -> Unit,
) {
    private class Decoded(
        val photo: PhotoEntry,
        val file: File,
        val bitmap: Bitmap,
        /** What the bitmap was trimmed for. */
        val target: Target,
    )

    private val main = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor { r -> Thread(r, "decode").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private var current: Decoded? = null
    private var preloaded: Decoded? = null
    private var preloading: Pair<String, File>? = null
    private var generation = 0L
    private var animating = false
    private var pendingOld: Decoded? = null
    private var lastNext: Pair<PhotoEntry, File>? = null

    /** What the views show at the moment: bitmaps are trimmed to it, see [BitmapFit]. */
    private data class Target(
        val width: Int,
        val height: Int,
        val fill: Boolean,
    )

    private fun target() = Target(front.width, front.height, fillMode == FillMode.FILL)

    init {
        // A new size, for example after switching the frame's orientation, needs bitmaps trimmed for it.
        val onResize =
            View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) main.post { refit() }
            }
        front.addOnLayoutChangeListener(onResize)
        back.addOnLayoutChangeListener(onResize)
    }

    var fillMode: FillMode = FillMode.FILL
        set(value) {
            val changed = field != value
            field = value
            front.scaleType = scaleType()
            back.scaleType = scaleType()
            // The bitmaps were trimmed for the old mode.
            if (changed) refit()
        }

    /** Shows [photo] with the transition [transition]. [step] +1 comes from the right, -1 from the left. */
    fun show(
        photo: PhotoEntry,
        file: File,
        step: Int,
        transition: Transition,
        durationMs: Long,
    ) {
        val gen = ++generation
        val ready = preloaded?.takeIf { it.photo.id == photo.id && it.photo.version == photo.version && it.file == file }
        if (ready != null) {
            preloaded = null
            swapTo(ready, step, transition, durationMs)
            return
        }
        // Not decoded ahead, for example after swiping back: decode now.
        preloaded?.bitmap?.recycle()
        preloaded = null
        val target = target()
        decoder.execute {
            val bitmap = decode(file, target)
            main.post {
                if (gen != generation) {
                    bitmap?.recycle()
                } else if (bitmap == null) {
                    onUndecodable(photo)
                } else {
                    swapTo(Decoded(photo, file, bitmap, target), step, transition, durationMs)
                }
            }
        }
    }

    /** Decodes the next photo ahead so that the transition starts without waiting. */
    fun preload(next: Pair<PhotoEntry, File>?) {
        if (next == null) return
        lastNext = next
        val (photo, file) = next
        if (preloaded?.photo?.id == photo.id && preloaded?.file == file) return
        if (preloading == photo.id to file) return
        // At most two bitmaps: during a transition both are in use.
        if (animating) {
            main.postDelayed({ preload(next) }, 200)
            return
        }
        preloaded?.bitmap?.recycle()
        preloaded = null
        preloading = photo.id to file
        val target = target()
        decoder.execute {
            val bitmap = decode(file, target)
            main.post {
                preloading = null
                if (bitmap == null) return@post
                if (current?.photo?.id == photo.id) {
                    bitmap.recycle()
                } else {
                    preloaded?.bitmap?.recycle()
                    preloaded = Decoded(photo, file, bitmap, target)
                }
            }
        }
    }

    /**
     * The fill mode changed: the shown photo and the one decoded ahead are decoded again for the new mode.
     * A running transition is waited for.
     */
    private fun refit() {
        val shown = current ?: return
        val target = target()
        if (shown.target == target) return
        if (animating) {
            main.postDelayed({ refit() }, 200)
            return
        }
        generation++
        preloaded?.bitmap?.recycle()
        preloaded = null
        preloading = null
        decoder.execute {
            val bitmap = decode(shown.file, target)
            main.post {
                if (bitmap == null || current !== shown || animating) {
                    bitmap?.recycle()
                    return@post
                }
                val replacement = Decoded(shown.photo, shown.file, bitmap, target)
                current = replacement
                front.setImageBitmap(bitmap)
                shown.bitmap.recycle()
                preload(lastNext)
            }
        }
    }

    /** Shows nothing any more, for example in the empty state. */
    fun clear() {
        generation++
        front.animate().cancel()
        back.animate().cancel()
        front.setImageDrawable(null)
        back.setImageDrawable(null)
        current?.bitmap?.recycle()
        preloaded?.bitmap?.recycle()
        current = null
        preloaded = null
    }

    fun release() {
        clear()
        decoder.shutdownNow()
    }

    private fun swapTo(
        next: Decoded,
        step: Int,
        transition: Transition,
        durationMs: Long,
    ) {
        // A running transition is completed before the next one begins. cancel() does not trigger withEndAction.
        if (animating) {
            front.animate().cancel()
            back.animate().cancel()
            done(pendingOld)
        }
        back.scaleType = scaleType()
        back.setImageBitmap(next.bitmap)
        back.translationX = 0f
        back.alpha = 1f
        val old = current
        current = next
        val width = front.width.toFloat().coerceAtLeast(1f)
        val instant = transition == Transition.NONE || durationMs <= 0 || old == null
        if (instant) {
            raiseBack()
            exchangeViews()
            finishSwap(old)
            return
        }
        animating = true
        pendingOld = old
        when (transition) {
            Transition.FADE -> {
                back.alpha = 0f
                raiseBack()
                back
                    .animate()
                    .alpha(1f)
                    .setDuration(durationMs)
                    .withEndAction { done(old) }
                    .start()
            }

            Transition.SLIDE -> {
                val direction = if (step < 0) -1f else 1f
                back.translationX = width * direction
                raiseBack()
                back
                    .animate()
                    .translationX(0f)
                    .setDuration(durationMs)
                    .withEndAction { done(old) }
                    .start()
                front
                    .animate()
                    .translationX(-width * direction)
                    .setDuration(durationMs)
                    .start()
            }

            Transition.NONE -> {
                Unit
            }
        }
    }

    private fun done(old: Decoded?) {
        animating = false
        pendingOld = null
        exchangeViews()
        finishSwap(old)
    }

    /** The back view to the front, but below the overlays (their translationZ is higher). */
    private fun raiseBack() {
        back.translationZ = 1f
        front.translationZ = 0f
    }

    private fun exchangeViews() {
        val t = front
        front = back
        back = t
    }

    /** Clears the back view and releases the old bitmap. */
    private fun finishSwap(old: Decoded? = null) {
        back.setImageDrawable(null)
        back.alpha = 0f
        back.translationX = 0f
        front.alpha = 1f
        front.translationX = 0f
        if (old != null && old !== current) old.bitmap.recycle()
    }

    private fun scaleType() = if (fillMode == FillMode.FILL) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER

    /** The photo from [file], trimmed for [target]. `null` if it cannot be decoded. */
    private fun decode(
        file: File,
        target: Target,
    ): Bitmap? =
        try {
            BitmapFactory
                .decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                ?.let { BitmapFit.forView(it, target.width, target.height, target.fill) }
        } catch (e: OutOfMemoryError) {
            null
        }
}
