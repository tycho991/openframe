@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.tycho991.openframe.core.slideshow

import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.RenderPriority
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.settings.FillMode
import io.github.tycho991.openframe.core.settings.Order
import io.github.tycho991.openframe.core.settings.Settings
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Spec `slideshow-display` with virtual time. */
class SlideshowControllerTest {
    private fun photo(n: Int): PhotoEntry {
        val path = "p%02d.jpg".format(n)
        return PhotoEntry(PhotoEntry.idFor("local", path), "local", path, 4, 3, n * 1000L, 1, 1, 1)
    }

    private class FakeImages : DisplayImages {
        val readyIds = mutableSetOf<String>()
        val broken = mutableSetOf<String>()
        val prefetched = mutableListOf<Pair<String, RenderPriority>>()
        val pending = mutableMapOf<String, CompletableDeferred<File>>()
        val demanded = mutableListOf<String>()

        /** Part of the file names, so a new presentation gives new files. */
        var variant = ""

        override fun ready(entry: PhotoEntry): File? = if (entry.id in readyIds) File(entry.path + variant) else null

        override suspend fun render(entry: PhotoEntry): File {
            demanded += entry.id
            if (entry.id in broken) throw IllegalStateException("broken")
            return pending.getOrPut(entry.id) { CompletableDeferred() }.await().also { readyIds += entry.id }
        }

        override fun prefetch(
            entries: List<PhotoEntry>,
            priority: RenderPriority,
        ) {
            entries.forEach { prefetched += it.id to priority }
        }
    }

    private class Setup(
        scope: TestScope,
        photos: List<PhotoEntry>,
        allReady: Boolean = true,
        settings: Settings = Settings.defaults("x").copy(order = Order.CHRONOLOGICAL, intervalSeconds = 30),
    ) {
        val visible = MutableStateFlow(photos)
        val library = photos.map { it.id }.toMutableSet()
        val settings = MutableStateFlow(settings)
        val images = FakeImages().apply { if (allReady) readyIds += photos.map { it.id } }
        val controller =
            SlideshowController(visible, { it in library }, this.settings, images, scope.backgroundScope, AppLog(TestClock())).also {
                it.start()
                scope.runCurrent()
            }

        fun shown(): PhotoEntry? = (controller.state.value as? SlideState.Showing)?.photo
    }

    private val photos = (1..5).map(::photo)

    @Test
    fun `Change after interval and wrap-around`() =
        runTest {
            val s = Setup(this, photos)
            assertEquals(photos[0], s.shown())
            advanceTimeBy(29_999)
            runCurrent()
            assertEquals(photos[0], s.shown())
            advanceTimeBy(2)
            runCurrent()
            assertEquals(photos[1], s.shown(), "after 30 seconds the next visible photo")
            advanceTimeBy(30_000 * 4L)
            runCurrent()
            assertEquals(photos[0], s.shown(), "the first again after the last")
        }

    @Test
    fun `Swiping back restarts the interval`() =
        runTest {
            val s = Setup(this, photos)
            advanceTimeBy(30_001)
            runCurrent()
            advanceTimeBy(20_000)
            s.controller.previous()
            runCurrent()
            assertEquals(photos[0], s.shown())
            assertEquals(-1, (s.controller.state.value as SlideState.Showing).step)
            advanceTimeBy(29_000)
            runCurrent()
            assertEquals(photos[0], s.shown(), "the interval starts over")
            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(photos[1], s.shown())
        }

    @Test
    fun `Pause`() =
        runTest {
            val s = Setup(this, photos)
            s.controller.togglePause()
            runCurrent()
            assertTrue(s.controller.paused.value)
            advanceTimeBy(10 * 60_000L)
            runCurrent()
            assertEquals(photos[0], s.shown(), "stays until resumed")
            s.controller.togglePause()
            runCurrent()
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(photos[1], s.shown())
        }

    @Test
    fun `A new fill mode shows the current photo again at once, also while paused`() =
        runTest {
            val s = Setup(this, photos)
            s.controller.togglePause()
            runCurrent()
            val before = s.controller.state.value as SlideState.Showing
            s.images.variant = "-fit"
            s.settings.value = s.settings.value.copy(fillMode = FillMode.FIT)
            runCurrent()
            assertTrue(photos[0].id in s.images.demanded, "the photo on screen is rendered for the new fill mode")
            s.images.pending
                .getValue(photos[0].id)
                .complete(File(photos[0].path + "-fit"))
            runCurrent()
            val after = s.controller.state.value as SlideState.Showing
            assertEquals(photos[0], after.photo)
            assertEquals(File(photos[0].path + "-fit"), after.file)
            assertTrue(after.seq > before.seq)
            assertTrue(s.controller.paused.value, "still paused")
            advanceTimeBy(10 * 60_000L)
            runCurrent()
            assertEquals(photos[0], s.shown())
        }

    @Test
    fun `Interval takes effect at the next change`() =
        runTest {
            val s = Setup(this, photos)
            s.settings.value = s.settings.value.copy(intervalSeconds = 5)
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(photos[1], s.shown())
            advanceTimeBy(5_001)
            runCurrent()
            assertEquals(photos[2], s.shown())
        }

    @Test
    fun `Photos not yet rendered are skipped and rendered ahead`() =
        runTest {
            val s = Setup(this, photos)
            s.images.readyIds -= photos[1].id
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(photos[2], s.shown())
            assertTrue(photos[1].id to RenderPriority.NEXT in s.images.prefetched)
        }

    @Test
    fun `Next photo is prepared`() =
        runTest {
            val s = Setup(this, photos)
            assertEquals(
                photos[1],
                s.controller.nextUp.value
                    ?.first,
            )
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(
                photos[2],
                s.controller.nextUp.value
                    ?.first,
            )
        }

    @Test
    fun `Next photos are rendered ahead`() =
        runTest {
            val s = Setup(this, photos)
            assertTrue(s.images.prefetched.contains(photos[1].id to RenderPriority.NEXT))
            assertTrue(s.images.prefetched.contains(photos[2].id to RenderPriority.NEXT))
            assertTrue(s.images.prefetched.contains(photos[3].id to RenderPriority.PREFETCH))
        }

    @Test
    fun `First start with an empty cache renders the first photo first`() =
        runTest {
            val s = Setup(this, photos, allReady = false)
            assertEquals(SlideState.Loading, s.controller.state.value)
            assertEquals(listOf(photos[0].id), s.images.demanded, "the first photo of the order first")
            s.images.pending
                .getValue(photos[0].id)
                .complete(File("p01.jpg"))
            runCurrent()
            assertEquals(photos[0], s.shown())
        }

    @Test
    fun `Broken file on first render`() =
        runTest {
            val images = FakeImages().apply { broken += photos[0].id }
            val s = Setup(this, photos, allReady = false)
            // Own setup with a broken first photo.
            val controller =
                SlideshowController(
                    MutableStateFlow(photos),
                    { true },
                    s.settings,
                    images,
                    backgroundScope,
                    AppLog(TestClock()),
                ).also { it.start() }
            runCurrent()
            assertEquals(listOf(photos[0].id, photos[1].id), images.demanded, "after the error the photo that follows")
            images.pending.getValue(photos[1].id).complete(File("p02.jpg"))
            runCurrent()
            assertEquals(photos[1], (controller.state.value as SlideState.Showing).photo)
        }

    @Test
    fun `Broken file in the display`() =
        runTest {
            val s = Setup(this, photos)
            s.controller.reportUndecodable(photos[0])
            runCurrent()
            assertEquals(photos[1], s.shown(), "shows the photo that follows instead")
        }

    @Test
    fun `Current photo deleted`() =
        runTest {
            val s = Setup(this, photos)
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(photos[1], s.shown())
            s.library -= photos[1].id
            s.visible.value = photos - photos[1]
            runCurrent()
            assertEquals(photos[2], s.shown(), "immediately to the next photo")
        }

    @Test
    fun `Hidden album takes effect at the next change`() =
        runTest {
            val s = Setup(this, photos)
            s.visible.value = listOf(photos[0], photos[3])
            runCurrent()
            assertEquals(photos[0], s.shown(), "hidden, not deleted: no immediate change")
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(photos[3], s.shown())
        }

    // Spec `display-schedule` › Slideshow rests while the display is off.

    @Test
    fun `Morning`() =
        runTest {
            val s = Setup(this, photos)
            s.controller.setResting(true)
            runCurrent()
            advanceTimeBy(9 * 3600_000L)
            runCurrent()
            assertEquals(photos[0], s.shown(), "no change through the night")
            s.controller.setResting(false)
            runCurrent()
            assertEquals(photos[1], s.shown(), "a new photo at once")
            advanceTimeBy(29_999)
            runCurrent()
            assertEquals(photos[1], s.shown())
            advanceTimeBy(2)
            runCurrent()
            assertEquals(photos[2], s.shown(), "the interval starts over")
        }

    @Test
    fun `Paused before the night`() =
        runTest {
            val s = Setup(this, photos)
            s.controller.setPaused(true)
            s.controller.setResting(true)
            runCurrent()
            advanceTimeBy(9 * 3600_000L)
            s.controller.setResting(false)
            runCurrent()
            advanceTimeBy(10 * 60_000L)
            runCurrent()
            assertEquals(photos[0], s.shown(), "the same photo")
            assertTrue(s.controller.paused.value, "still paused")
        }

    @Test
    fun `Nothing is rendered ahead while resting`() =
        runTest {
            val s = Setup(this, photos)
            s.controller.setResting(true)
            runCurrent()
            s.images.prefetched.clear()
            // Swipes, ticks, a broken file and a changed library do not render anything for the display.
            s.controller.next()
            s.controller.reportUndecodable(photos[0])
            s.controller.setPaused(true)
            s.controller.setPaused(false)
            s.visible.value = photos + photo(6)
            runCurrent()
            advanceTimeBy(3600_000L)
            runCurrent()
            assertEquals(emptyList<Pair<String, RenderPriority>>(), s.images.prefetched)
            assertEquals(emptyList<String>(), s.images.demanded)
            assertEquals(photos[0], s.shown())
        }

    @Test
    fun `A deleted current photo is replaced only when resting ends`() =
        runTest {
            val s = Setup(this, photos)
            s.controller.setPaused(true)
            s.controller.setResting(true)
            runCurrent()
            s.library -= photos[0].id
            s.visible.value = photos - photos[0]
            runCurrent()
            assertEquals(photos[0], s.shown(), "not while the display is off")
            s.controller.setResting(false)
            runCurrent()
            assertEquals(photos[1], s.shown(), "replaced, although paused")
        }

    @Test
    fun `All photos gone while resting`() =
        runTest {
            val s = Setup(this, photos)
            s.controller.setResting(true)
            runCurrent()
            s.library.clear()
            s.visible.value = emptyList()
            runCurrent()
            s.controller.setResting(false)
            runCurrent()
            assertEquals(SlideState.Empty, s.controller.state.value)
        }

    @Test
    fun `Empty state and new photo`() =
        runTest {
            val s = Setup(this, emptyList())
            assertEquals(SlideState.Empty, s.controller.state.value)
            s.images.readyIds += photos[0].id
            s.library += photos[0].id
            s.visible.value = listOf(photos[0])
            runCurrent()
            assertEquals(photos[0], s.shown())
            s.visible.value = emptyList()
            runCurrent()
            assertEquals(SlideState.Empty, s.controller.state.value)
        }
}
