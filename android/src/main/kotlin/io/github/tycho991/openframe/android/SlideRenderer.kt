package io.github.tycho991.openframe.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.settings.Transition
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Shows photos with two stacked `ImageView`s. Decoding happens on a thread of its own, only the pre-rendered display
 * size. The display size already is the part of the photo the frame shows; the fill mode and the section change only
 * the rendered files. [BitmapFit] scales each decoded bitmap down to the size of the views and pads it to their aspect
 * ratio with black pixels, so the borders are part of the bitmap and fade with it, and the views have no background.
 * There are at most two bitmaps: the current and the next.
 * Transitions run through `ViewPropertyAnimator`. Deliberately without `withLayer()`: an `ImageView` without a
 * background does not overlap itself, so `alpha` and `translationX` need no
 * hardware layer. The layer cost about every fifth vsync on the Frameo (measured: 22 % missed
 * with it, 2 to 4 % without).
 * Every swap is reported to [onSwap] the moment it starts, so that what belongs to a photo (its caption) changes
 * with it and not when the state changes, which can come before the photo is decoded.
 */
class SlideRenderer(
    private var front: ImageView,
    private var back: ImageView,
    /** A photo could not be decoded. */
    private val onUndecodable: (PhotoEntry) -> Unit,
    /** Decodes the bitmaps; a test passes one that runs at once or when it says. */
    private val decoder: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "decode").apply { priority = Thread.NORM_PRIORITY - 1 } },
    /** The swap to the slide `seq` starts: animated over `durationMs`, or at once. */
    private val onSwap: (seq: Long, animated: Boolean, durationMs: Long) -> Unit = { _, _, _ -> },
) {
    private class Decoded(
        val photo: PhotoEntry,
        val file: File,
        val bitmap: Bitmap,
        /** What the bitmap was scaled for. */
        val target: Target,
    )

    private val main = Handler(Looper.getMainLooper())
    private var current: Decoded? = null
    private var preloaded: Decoded? = null
    private var preloading: Pair<String, File>? = null
    private var generation = 0L
    private var animating = false
    private var pendingOld: Decoded? = null
    private var lastNext: Pair<PhotoEntry, File>? = null

    /** A [preload] or a [refit] came during a transition; [done] runs it afterwards, instead of polling. */
    private var preloadPending = false
    private var refitPending = false

    /** Raised by [clear], so that a decode of [preload] that ends after it is dropped; [generation] is raised by [show]. */
    private var clears = 0L

    /** [release] ran: nothing is decoded any more. */
    private var released = false

    /** The photo decoded ahead, for tests. */
    internal val preloadedId: String? get() = preloaded?.photo?.id

    /** The size of the views at the moment: bitmaps are scaled down to it, see [BitmapFit]. */
    private data class Target(
        val width: Int,
        val height: Int,
    )

    private fun target() = Target(front.width, front.height)

    init {
        // The black borders are part of the bitmaps (BitmapFit), so a fade blends them too; a background of the views
        // instead would make them overlap themselves and need a layer for `alpha`.
        for (view in listOf(front, back)) view.scaleType = ImageView.ScaleType.FIT_CENTER
        // A new size, for example after switching the frame's orientation, needs bitmaps scaled for it.
        val onResize =
            View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) main.post { refit() }
            }
        front.addOnLayoutChangeListener(onResize)
        back.addOnLayoutChangeListener(onResize)
    }

    /**
     * Shows [photo], the slide [seq], with the transition [transition]. [step] +1 comes from the right, -1 from the
     * left.
     */
    fun show(
        photo: PhotoEntry,
        file: File,
        step: Int,
        seq: Long,
        transition: Transition,
        durationMs: Long,
    ) {
        if (released) return
        val gen = ++generation
        val ready = preloaded?.takeIf { it.photo.id == photo.id && it.photo.version == photo.version && it.file == file }
        if (ready != null) {
            preloaded = null
            swapTo(ready, step, seq, transition, durationMs)
            return
        }
        // Not decoded ahead, for example after swiping back: decode now.
        preloaded?.bitmap?.recycle()
        preloaded = null
        val target = target()
        decoder.execute {
            val bitmap = decode(file, target)
            main.post {
                if (gen != generation || released) {
                    bitmap?.recycle()
                } else if (bitmap == null) {
                    onUndecodable(photo)
                } else {
                    swapTo(Decoded(photo, file, bitmap, target), step, seq, transition, durationMs)
                }
            }
        }
    }

    /** Decodes the next photo ahead so that the transition starts without waiting. */
    fun preload(next: Pair<PhotoEntry, File>?) {
        if (next == null || released) return
        lastNext = next
        val (photo, file) = next
        if (preloaded?.photo?.id == photo.id && preloaded?.file == file) return
        if (preloading == photo.id to file) return
        // At most two bitmaps: during a transition both are in use. The end of the transition decodes it.
        if (animating) {
            preloadPending = true
            return
        }
        preloaded?.bitmap?.recycle()
        preloaded = null
        preloading = photo.id to file
        val target = target()
        val clearsAtStart = clears
        decoder.execute {
            val bitmap = decode(file, target)
            main.post {
                if (released || clears != clearsAtStart) {
                    bitmap?.recycle()
                    return@post
                }
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
     * The views changed their size: the shown photo and the one decoded ahead are decoded again for it.
     * A running transition is waited for.
     */
    private fun refit() {
        if (released) return
        val shown = current ?: return
        val target = target()
        if (shown.target == target) return
        if (animating) {
            refitPending = true
            return
        }
        generation++
        preloaded?.bitmap?.recycle()
        preloaded = null
        preloading = null
        decoder.execute {
            val bitmap = decode(shown.file, target)
            main.post {
                if (bitmap == null || released || current !== shown || animating) {
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

    /**
     * Shows nothing any more, for example in the empty state. A transition cancelled halfway leaves nothing behind:
     * its old bitmap is recycled, and the views are back at their place, opaque and on the same level. A decode that
     * ends afterwards is dropped.
     */
    fun clear() {
        generation++
        clears++
        // cancel() does not run withEndAction, so what done() would do happens here.
        front.animate().cancel()
        back.animate().cancel()
        animating = false
        pendingOld?.let { if (it !== current) it.bitmap.recycle() }
        pendingOld = null
        front.setImageDrawable(null)
        back.setImageDrawable(null)
        current?.bitmap?.recycle()
        preloaded?.bitmap?.recycle()
        current = null
        preloaded = null
        preloading = null
        preloadPending = false
        refitPending = false
        for (view in listOf(front, back)) {
            view.translationX = 0f
            view.alpha = 1f
            view.translationZ = 0f
        }
    }

    /**
     * Ends the renderer with its activity: no decode starts afterwards, so none can be refused on the main thread, and
     * one still running ends on its own and is dropped.
     */
    fun release() {
        released = true
        clear()
        main.removeCallbacksAndMessages(null)
        decoder.shutdown()
    }

    private fun swapTo(
        next: Decoded,
        step: Int,
        seq: Long,
        transition: Transition,
        durationMs: Long,
    ) {
        // A running transition is completed before the next one begins. cancel() does not trigger withEndAction.
        if (animating) {
            front.animate().cancel()
            back.animate().cancel()
            done(pendingOld)
        }
        back.setImageBitmap(next.bitmap)
        back.translationX = 0f
        back.alpha = 1f
        val old = current
        current = next
        val width = front.width.toFloat().coerceAtLeast(1f)
        val instant = transition == Transition.NONE || durationMs <= 0 || old == null
        onSwap(seq, !instant, if (instant) 0 else durationMs)
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
        // What came during the transition, posted: a swapTo that ends the transition itself finishes first, so the photo
        // it shows is not decoded twice.
        if (preloadPending || refitPending) {
            main.post {
                val refitting = refitPending
                val preloading = preloadPending
                refitPending = false
                preloadPending = false
                if (refitting) refit()
                if (preloading) preload(lastNext)
            }
        }
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

    /** The photo from [file], scaled down for [target]. `null` if it cannot be decoded. */
    private fun decode(
        file: File,
        target: Target,
    ): Bitmap? =
        try {
            BitmapFactory
                .decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                ?.let { BitmapFit.forView(it, target.width, target.height) }
        } catch (e: OutOfMemoryError) {
            null
        }
}
