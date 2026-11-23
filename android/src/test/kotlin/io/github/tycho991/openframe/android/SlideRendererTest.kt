package io.github.tycho991.openframe.android

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.widget.ImageView
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.settings.Transition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * Spec `slideshow-display`: Memory limits › Endurance run: the slideshow view leaves nothing behind when it is cleared
 * or released during a transition, and does not poll during one (design decision 12 of `library-performance`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class SlideRendererTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Runs each decode at once, or holds it until [runAll]; counts them. */
    private class Decoder : AbstractExecutorService() {
        var immediate = true
        var executed = 0
        private val held = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            executed++
            if (immediate) command.run() else held += command
        }

        fun runAll() {
            while (held.isNotEmpty()) held.removeFirst().run()
        }

        override fun shutdown() {}

        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()

        override fun isShutdown() = false

        override fun isTerminated() = false

        override fun awaitTermination(
            timeout: Long,
            unit: TimeUnit,
        ) = true
    }

    private val context get() = RuntimeEnvironment.getApplication()
    private val a = view()
    private val b = view()
    private val decoder = Decoder()
    private val renderer = SlideRenderer(a, b, {}, decoder = decoder)
    private val looper get() = shadowOf(Looper.getMainLooper())

    private fun view() = ImageView(RuntimeEnvironment.getApplication()).apply { layout(0, 0, 800, 480) }

    private fun photo(name: String): Pair<PhotoEntry, File> {
        val stream = requireNotNull(javaClass.getResourceAsStream("/fixtures/landscape.jpg"))
        val file = File(tmp.root, "$name.jpg").also { f -> stream.use { s -> f.outputStream().use { s.copyTo(it) } } }
        return PhotoEntry(PhotoEntry.idFor("local", "$name.jpg"), "local", "$name.jpg", 400, 300, 0, 1, 1, 1) to file
    }

    private fun show(
        photo: Pair<PhotoEntry, File>,
        seq: Long,
        transition: Transition = Transition.FADE,
    ) {
        renderer.show(photo.first, photo.second, 1, seq, transition, 1_000)
        looper.idle()
    }

    /**
     * Starts the transition to [photo] and stops in its middle: only the posted swap runs, not the frames of the
     * animation, which Robolectric would run to the end at once for views without a window.
     */
    private fun startTransition(
        photo: Pair<PhotoEntry, File>,
        seq: Long,
        transition: Transition = Transition.FADE,
    ) {
        renderer.show(photo.first, photo.second, 1, seq, transition, 1_000)
        looper.runOneTask()
    }

    private fun bitmapOf(view: ImageView): Bitmap? = (view.drawable as? BitmapDrawable)?.bitmap

    private fun assertViewsReset() {
        for (view in listOf(a, b)) {
            assertEquals(0f, view.translationX)
            assertEquals(1f, view.alpha)
            assertEquals(0f, view.translationZ)
        }
    }

    @Test
    fun `A preload during a fade decodes exactly once after it ends`() {
        show(photo("a"), 1)
        startTransition(photo("b"), 2)
        val decodes = decoder.executed
        val next = photo("c")
        renderer.preload(next)
        renderer.preload(next)
        assertEquals("nothing decoded during the fade", decodes, decoder.executed)
        looper.idleFor(Duration.ofMillis(1_200))
        assertEquals(decodes + 1, decoder.executed)
        assertEquals(next.first.id, renderer.preloadedId)
        looper.idleFor(Duration.ofSeconds(5))
        assertEquals("no polling", decodes + 1, decoder.executed)
    }

    @Test
    fun `Clear in the middle of a fade leaves no runnable, no bitmap and no half-faded view`() {
        show(photo("a"), 1)
        startTransition(photo("b"), 2)
        val bitmaps = listOfNotNull(bitmapOf(a), bitmapOf(b))
        assertEquals("the old and the new photo during the fade", 2, bitmaps.size)
        renderer.preload(photo("c"))
        val decodes = decoder.executed
        renderer.clear()
        looper.idleFor(Duration.ofSeconds(5))
        assertTrue("the outgoing bitmap is recycled", bitmaps.all { it.isRecycled })
        assertEquals(Duration.ZERO, looper.nextScheduledTaskTime)
        assertEquals("the waiting preload is dropped", decodes, decoder.executed)
        assertNull(renderer.preloadedId)
        assertViewsReset()
    }

    @Test
    fun `Clear in the middle of a slide puts the views back`() {
        show(photo("a"), 1, Transition.SLIDE)
        startTransition(photo("b"), 2, Transition.SLIDE)
        assertTrue("halfway", a.translationX != 0f || b.translationX != 0f)
        assertTrue("on top", a.translationZ != 0f || b.translationZ != 0f)
        renderer.clear()
        looper.idle()
        assertViewsReset()
    }

    @Test
    fun `A decode that ends after clear or release changes nothing and throws nothing`() {
        show(photo("a"), 1)
        decoder.immediate = false
        renderer.preload(photo("c"))
        renderer.clear()
        decoder.runAll()
        looper.idle()
        assertNull(renderer.preloadedId)

        show(photo("a"), 2)
        decoder.runAll()
        looper.idle()
        renderer.preload(photo("d"))
        renderer.release()
        decoder.runAll()
        looper.idle()
        assertNull(renderer.preloadedId)
        val decodes = decoder.executed
        renderer.preload(photo("e"))
        show(photo("f"), 3)
        assertEquals("no decode after release", decodes, decoder.executed)
    }
}
