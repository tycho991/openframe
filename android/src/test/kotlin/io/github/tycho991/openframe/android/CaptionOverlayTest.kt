package io.github.tycho991.openframe.android

import android.graphics.Bitmap
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.tycho991.openframe.R
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.settings.CaptionSettings
import io.github.tycho991.openframe.core.settings.Corner
import io.github.tycho991.openframe.core.settings.Transition
import io.github.tycho991.openframe.core.slideshow.CaptionText
import io.github.tycho991.openframe.core.slideshow.ShownCaption
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** Spec `slideshow-display`: the caption over the photo, with the photo's swap, its length, corner and the hint. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class CaptionOverlayTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val root = LayoutInflater.from(context).inflate(R.layout.activity_display, null) as FrameLayout
    private val container = root.findViewById<FrameLayout>(R.id.caption)
    private val a = root.findViewById<LinearLayout>(R.id.caption_a)
    private val b = root.findViewById<LinearLayout>(R.id.caption_b)
    private val overlay = CaptionOverlay(container, a, b)
    private val renderer =
        SlideRenderer(root.findViewById<ImageView>(R.id.photo_a), root.findViewById(R.id.photo_b), {}, overlay::swapStarted)

    @After
    fun tearDown() = renderer.release()

    private fun layout(
        width: Int = 1024,
        height: Int = 600,
    ) {
        repeat(2) {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, width, height)
            // The maximum width is set after a layout of the screen.
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun photo(name: String) = PhotoEntry(PhotoEntry.idFor("local", name), "local", name, 64, 48, 0, 10, 1, 1)

    private fun file(name: String): File =
        File(tmp.root, name).also { f ->
            f.outputStream().use { Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) }
        }

    private fun line(view: LinearLayout) = view.findViewById<TextView>(R.id.caption_line)

    private fun place(view: LinearLayout) = view.findViewById<TextView>(R.id.caption_place)

    /** The line texts that can be seen, with their alpha; a view at alpha 0 or without text is not seen. */
    private fun seen() =
        listOf(a, b)
            .filter { it.alpha > 0f && line(it).visibility == View.VISIBLE }
            .associate { line(it).text.toString() to it.alpha }

    /** Waits for the decoder thread of the renderer and runs what it posts. */
    private fun waitForSwap(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("the swap did not start", condition())
    }

    private fun caption(
        seq: Long,
        line: String?,
        place: String? = null,
    ) = ShownCaption(seq, if (line == null && place == null) null else CaptionText(line, place))

    @Test
    fun `The caption switches at the swap and not at the state change`() {
        layout()
        val swaps = ArrayList<Long>()
        // What is on screen the moment each swap starts, and how long the caption's fade takes.
        val atSwap = ArrayList<Map<String, Float>>()
        val fades = ArrayList<Pair<Long, Long>>()
        val tracking =
            SlideRenderer(root.findViewById(R.id.photo_a), root.findViewById(R.id.photo_b), {}) { seq, animated, ms ->
                swaps += seq
                overlay.swapStarted(seq, animated, ms)
                atSwap += seen()
                fades += a.animate().duration to b.animate().duration
            }
        overlay.update(caption(1, "Grandma's 80th birthday"))
        tracking.show(photo("1.jpg"), file("1.jpg"), 0, 1, Transition.FADE, 1000)
        waitForSwap { swaps == listOf(1L) }
        assertEquals(mapOf("Grandma's 80th birthday" to 1f), seen())

        // The state moves on to slide 2 before its photo is decoded: the caption stays with the photo on screen.
        overlay.update(caption(2, null))
        assertEquals(mapOf("Grandma's 80th birthday" to 1f), seen())

        // Scenario "Crossfade": from a photo with a caption to one without. When the photo starts to fade, the caption
        // is still the old photo's and fades out over the same duration; afterwards there is none.
        tracking.show(photo("2.jpg"), file("2.jpg"), 1, 2, Transition.FADE, 1000)
        waitForSwap { swaps == listOf(1L, 2L) }
        assertEquals(mapOf("Grandma's 80th birthday" to 1f), atSwap[1])
        assertEquals(1000L to 1000L, fades[1])
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100))
        assertEquals(emptyMap<String, Float>(), seen())

        // And the other way round: the incoming caption starts invisible and fades in with its photo.
        overlay.update(caption(3, "Siena"))
        tracking.show(photo("3.jpg"), file("3.jpg"), 1, 3, Transition.SLIDE, 800)
        waitForSwap { swaps.size == 3 }
        assertEquals(emptyMap<String, Float>(), atSwap[2])
        assertEquals(800L to 800L, fades[2])
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900))
        assertEquals(mapOf("Siena" to 1f), seen())
        tracking.release()
    }

    @Test
    fun `A caption for the slide on screen replaces the text in place`() {
        layout()
        overlay.update(caption(1, "Old"))
        overlay.swapStarted(1, animated = false, durationMs = 0)
        overlay.update(caption(1, "New", "Siena, Italy"))
        assertEquals(mapOf("New" to 1f), seen())
        assertEquals("Siena, Italy", place(if (a.alpha == 1f) a else b).text.toString())
    }

    @Test
    fun `A caption that arrives after its swap is shown at once`() {
        layout()
        overlay.swapStarted(1, animated = true, durationMs = 1000)
        overlay.update(caption(1, "Late"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100))
        assertEquals(mapOf("Late" to 1f), seen())
    }

    @Test
    fun `Without a transition the caption changes at once`() {
        layout()
        val swaps = ArrayList<Boolean>()
        val tracking =
            SlideRenderer(root.findViewById(R.id.photo_a), root.findViewById(R.id.photo_b), {}) { seq, animated, ms ->
                swaps += animated
                overlay.swapStarted(seq, animated, ms)
            }
        // The first photo is shown at once.
        overlay.update(caption(1, "One"))
        tracking.show(photo("1.jpg"), file("1.jpg"), 0, 1, Transition.FADE, 1000)
        waitForSwap { swaps.size == 1 }
        // `none`, which is also what the display passes for the instant fallback of the TransitionGovernor.
        overlay.update(caption(2, "Two"))
        tracking.show(photo("2.jpg"), file("2.jpg"), 1, 2, Transition.NONE, 1000)
        waitForSwap { swaps.size == 2 }
        assertEquals(mapOf("Two" to 1f), seen())
        // A duration of 0.
        overlay.update(caption(3, "Three"))
        tracking.show(photo("3.jpg"), file("3.jpg"), 1, 3, Transition.SLIDE, 0)
        waitForSwap { swaps.size == 3 }
        assertEquals(mapOf("Three" to 1f), seen())
        assertEquals(listOf(false, false, false), swaps)
        tracking.release()
    }

    @Test
    fun `Long comment - two lines with an ellipsis and the place stays visible`() {
        layout()
        overlay.update(caption(1, "Grandma's 80th birthday in the garden ".repeat(13).take(500), "Siena, Italy"))
        overlay.swapStarted(1, animated = false, durationMs = 0)
        layout()
        val view = if (a.alpha == 1f) a else b
        val text = line(view)
        assertEquals(2, text.layout.lineCount)
        assertTrue("ellipsized", text.layout.getEllipsisCount(1) > 0)
        assertTrue("at most 60 % of the width, was ${text.width}", text.width <= 1024 * 0.6f + 1)
        assertEquals(View.VISIBLE, place(view).visibility)
        assertTrue("the place line has its height", place(view).height > 0)
        assertTrue("and stands below the comment", place(view).top >= text.bottom)
    }

    @Test
    fun `Caption top right`() {
        overlay.apply(CaptionSettings(position = Corner.TOP_RIGHT))
        overlay.update(caption(1, "Grandma's 80th birthday", "Siena, Italy"))
        overlay.swapStarted(1, animated = false, durationMs = 0)
        layout()
        assertEquals(Gravity.TOP or Gravity.RIGHT, (container.layoutParams as FrameLayout.LayoutParams).gravity)
        assertTrue("in the top right corner", container.right >= 1024 - 100 && container.top <= 100)
        for (view in listOf(a, b)) {
            assertEquals(Gravity.RIGHT, line(view).gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
            assertEquals(Gravity.RIGHT, place(view).gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
        }
        overlay.apply(CaptionSettings(position = Corner.BOTTOM_LEFT))
        layout()
        assertEquals(Gravity.LEFT, line(a).gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
        assertTrue("in the bottom left corner", container.left <= 100 && container.bottom >= 600 - 100)
    }

    @Test
    fun `Tap on the frame - the caption steps aside for the hint and comes back`() {
        overlay.update(caption(1, "Grandma's 80th birthday"))
        overlay.swapStarted(1, animated = false, durationMs = 0)
        overlay.hidden = true
        assertEquals(View.GONE, container.visibility)
        overlay.hidden = false
        assertEquals(View.VISIBLE, container.visibility)
        assertEquals(mapOf("Grandma's 80th birthday" to 1f), seen())
    }

    @Test
    fun `The caption moves above a clock in the same corner`() {
        overlay.apply(CaptionSettings(position = Corner.BOTTOM_RIGHT))
        overlay.update(caption(1, "Grandma's 80th birthday"))
        overlay.swapStarted(1, animated = false, durationMs = 0)
        layout()
        val bottom = container.bottom
        overlay.setOffsets(cornerOffsets(Corner.BOTTOM_RIGHT, true, 150, Corner.BOTTOM_RIGHT, gap = 8))
        layout()
        assertEquals(bottom - 158, container.bottom)
    }

    @Test
    fun `The empty state shows no caption`() {
        overlay.update(caption(1, "Grandma's 80th birthday"))
        overlay.swapStarted(1, animated = false, durationMs = 0)
        overlay.update(null)
        assertEquals(emptyMap<String, Float>(), seen())
    }
}
