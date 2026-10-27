package io.github.tycho991.openframe.android

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.tycho991.openframe.R
import io.github.tycho991.openframe.core.settings.CaptionSettings
import io.github.tycho991.openframe.core.slideshow.CaptionText
import io.github.tycho991.openframe.core.slideshow.ShownCaption

/**
 * The caption over the photo (spec `slideshow-display`, "Caption over the photo"), in its own corner, with the
 * clock's shadow. Two caption views cross-fade, so the caption changes with the photo: it switches only when
 * [SlideRenderer] reports that it starts the swap to a slide ([swapStarted]), never when the state changes, which can
 * come before the next photo is decoded. With a transition the outgoing caption fades out and the incoming one in over
 * the photo's duration; without one the text changes at once. A new text for the slide on screen replaces the
 * visible one in place.
 */
class CaptionOverlay(
    private val container: FrameLayout,
    first: LinearLayout,
    second: LinearLayout,
) {
    private val main = Handler(Looper.getMainLooper())
    private var front = first
    private var back = second
    private var settings = CaptionSettings()

    /** The latest caption from the core; it may belong to a slide that has not been swapped to yet. */
    private var latest: ShownCaption? = null

    /** The slide whose caption is on screen, `null` before the first swap or after [clear]. */
    private var shownSeq: Long? = null
    private val baseMargin = (container.layoutParams as FrameLayout.LayoutParams).bottomMargin
    private var offsets = CornerOffsets(0, 0)

    /** True while the hint is shown: the caption steps aside like the clock. */
    var hidden = false
        set(value) {
            field = value
            container.visibility = if (value) View.GONE else View.VISIBLE
        }

    init {
        for (view in listOf(first, second)) {
            val (line, place) = lines(view)
            FrameType.apply(FrameType.Weight.REGULAR, line, place)
        }
        // At most 60 % of the screen's width; the screen is the parent, and it changes with the orientation.
        (container.parent as? View)?.addOnLayoutChangeListener { parent, _, _, _, _, _, _, _, _ ->
            val max = (parent.width * MAX_WIDTH_FRACTION).toInt()
            // Not within the layout pass; a Handler and not View.post, which waits for the view to be attached.
            if (max > 0 && lines(front).first.maxWidth != max) main.post { setMaxWidth(max) }
        }
    }

    fun apply(caption: CaptionSettings) {
        settings = caption
        val (vertical, horizontal) = cornerGravity(caption.position)
        (container.layoutParams as FrameLayout.LayoutParams).gravity = vertical or horizontal
        for (view in listOf(front, back)) {
            view.gravity = horizontal
            val (line, place) = lines(view)
            line.gravity = horizontal
            place.gravity = horizontal
            (view.layoutParams as FrameLayout.LayoutParams).gravity = vertical or horizontal
        }
        applyOffsets()
    }

    /** The extra margin towards the middle when the caption shares its corner with the clock ([cornerOffsets]). */
    fun setOffsets(offsets: CornerOffsets) {
        if (offsets == this.offsets) return
        this.offsets = offsets
        applyOffsets()
    }

    /** A new caption from the core. Shown now only if it belongs to the slide on screen. */
    fun update(caption: ShownCaption?) {
        latest = caption
        if (caption == null) {
            clear()
            return
        }
        if (caption.seq == shownSeq) setText(front, caption.text)
    }

    /** [SlideRenderer] starts the swap to slide [seq], [animated] over [durationMs] or at once. */
    fun swapStarted(
        seq: Long,
        animated: Boolean,
        durationMs: Long,
    ) {
        val text = latest?.takeIf { it.seq == seq }?.text
        shownSeq = seq
        finishFade()
        if (!animated) {
            setText(front, text)
            front.alpha = 1f
            return
        }
        val outgoing = front
        val incoming = back
        setText(incoming, text)
        incoming.alpha = 0f
        incoming
            .animate()
            .alpha(1f)
            .setDuration(durationMs)
            .start()
        outgoing
            .animate()
            .alpha(0f)
            .setDuration(durationMs)
            .start()
        front = incoming
        back = outgoing
    }

    /** No photo on screen, for example the empty state: no caption either. */
    fun clear() {
        shownSeq = null
        finishFade()
        setText(front, null)
        setText(back, null)
    }

    /** A cross-fade still running ends at its end state before the next one begins. */
    private fun finishFade() {
        front.animate().cancel()
        back.animate().cancel()
        front.alpha = 1f
        back.alpha = 0f
        setText(back, null)
    }

    private fun setText(
        view: LinearLayout,
        text: CaptionText?,
    ) {
        val (line, place) = lines(view)
        line.text = text?.line.orEmpty()
        line.visibility = if (text?.line != null) View.VISIBLE else View.GONE
        place.text = text?.place.orEmpty()
        place.visibility = if (text?.place != null) View.VISIBLE else View.GONE
    }

    private fun setMaxWidth(max: Int) {
        for (view in listOf(front, back)) {
            val (line, place) = lines(view)
            line.maxWidth = max
            place.maxWidth = max
        }
    }

    private fun applyOffsets() {
        val params = container.layoutParams as FrameLayout.LayoutParams
        params.topMargin = baseMargin + offsets.top
        params.bottomMargin = baseMargin + offsets.bottom
        container.layoutParams = params
    }

    private fun lines(view: LinearLayout): Pair<TextView, TextView> =
        view.findViewById<TextView>(R.id.caption_line) to view.findViewById(R.id.caption_place)

    companion object {
        const val MAX_WIDTH_FRACTION = 0.6f
    }
}
