@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.tycho991.openframe.core.slideshow

import io.github.tycho991.openframe.core.library.Crop
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.RenderPriority
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.settings.FillMode
import io.github.tycho991.openframe.core.settings.Order
import io.github.tycho991.openframe.core.settings.Orientation
import io.github.tycho991.openframe.core.settings.Settings
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Spec `slideshow-display` with virtual time. */
class SlideshowControllerTest {
    private fun photo(n: Int): PhotoEntry {
        val path = "p%02d.jpg".format(n)
        return PhotoEntry(PhotoEntry.idFor("local", path), "local", path, 4, 3, n * 1000L, 1, 1, 1)
    }

    /**
     * Display sizes as the render cache keeps them: a photo in [readyIds] has a finished file. A new presentation
     * ([variant]) or section makes it an older one: [ready] still returns it, [current] not until it is rendered again.
     */
    private class FakeImages : DisplayImages {
        val readyIds = mutableSetOf<String>()
        val broken = mutableSetOf<String>()
        val outOfMemory = mutableSetOf<String>()
        val prefetched = mutableListOf<Pair<String, RenderPriority>>()
        val pending = mutableMapOf<String, CompletableDeferred<File>>()
        val demanded = mutableListOf<String>()
        val priorities = mutableListOf<Pair<String, RenderPriority>>()

        /** Variant and section each finished file was rendered for; without an entry, for the current ones. */
        private val renderedVariant = mutableMapOf<String, String>()
        private val renderedCrop = mutableMapOf<String, Crop?>()

        /** Part of the file names, so a new presentation gives new files. The finished ones keep the old one. */
        var variant = ""
            set(value) {
                readyIds.forEach { renderedVariant.putIfAbsent(it, field) }
                field = value
            }

        /** [ready] throws, so that handling a step fails. */
        var failing = false

        /** The files of [photos] are rendered for their sections as they are now. */
        fun renderedFor(photos: List<PhotoEntry>) = photos.forEach { renderedCrop[it.id] = it.crop }

        private fun file(
            entry: PhotoEntry,
            variant: String,
            crop: Crop?,
        ) = File(entry.path + variant + (crop?.let { "-${it.x}-${it.y}-${it.zoom}" } ?: ""))

        private fun shownVariant(entry: PhotoEntry) = renderedVariant[entry.id] ?: variant

        private fun shownCrop(entry: PhotoEntry) = if (entry.id in renderedCrop) renderedCrop[entry.id] else entry.crop

        override fun ready(entry: PhotoEntry): File? {
            check(!failing) { "failing" }
            return if (entry.id in readyIds) file(entry, shownVariant(entry), shownCrop(entry)) else null
        }

        override fun current(entry: PhotoEntry): File? =
            if (entry.id in readyIds && shownVariant(entry) == variant && shownCrop(entry) == entry.crop) {
                file(entry, variant, entry.crop)
            } else {
                null
            }

        override suspend fun render(
            entry: PhotoEntry,
            priority: RenderPriority,
        ): File {
            demanded += entry.id
            priorities += entry.id to priority
            if (entry.id in broken) throw IllegalStateException("broken")
            if (entry.id in outOfMemory) throw OutOfMemoryError("test")
            return pending.getOrPut(entry.id) { CompletableDeferred() }.await().also {
                readyIds += entry.id
                renderedVariant[entry.id] = variant
                renderedCrop[entry.id] = entry.crop
                pending.remove(entry.id)
            }
        }

        /** Every window set with [ahead]: the IDs of next and later, and the files kept. */
        val windows = mutableListOf<Triple<List<String>, List<String>, List<File>>>()

        override fun ahead(
            next: List<PhotoEntry>,
            later: List<PhotoEntry>,
            keep: List<File>,
        ) {
            next.forEach { prefetched += it.id to RenderPriority.NEXT }
            later.forEach { prefetched += it.id to RenderPriority.PREFETCH }
            windows += Triple(next.map { it.id }, later.map { it.id }, keep)
        }
    }

    private class Setup(
        scope: TestScope,
        photos: List<PhotoEntry>,
        allReady: Boolean = true,
        settings: Settings = Settings.defaults("x").copy(order = Order.CHRONOLOGICAL, intervalSeconds = 30),
        configure: FakeImages.() -> Unit = {},
    ) {
        val visible = MutableStateFlow(photos)
        val library = photos.map { it.id }.toMutableSet()
        val settings = MutableStateFlow(settings)
        val images =
            FakeImages().apply {
                if (allReady) {
                    readyIds += photos.map { it.id }
                    renderedFor(photos)
                }
                configure()
            }
        val controller =
            SlideshowController(visible, { it in library }, this.settings, images, scope.backgroundScope, AppLog(TestClock())).also {
                it.start()
                scope.runCurrent()
            }

        fun shown(): PhotoEntry? = (controller.state.value as? SlideState.Showing)?.photo
    }

    private val photos = (1..5).map(::photo)

    /**
     * Spec `slideshow-display`: Bounded rendering ahead › Cold cache with 5,000 photos, › Server of all photos
     * unreachable; counted with 1,000 photos (design decision 13 of `library-performance`).
     */
    @Test
    fun `A cold cache of 1,000 photos asks for at most 10 ahead and renders at most 3 on demand`() =
        runTest {
            val many = (1..1000).map(::photo)
            val s = Setup(this, many, allReady = false) { broken += many.map { it.id } }
            runCurrent()
            val ahead =
                s.images.prefetched
                    .map { it.first }
                    .toSet()
            assertTrue(ahead.size <= 10, "${ahead.size} photos asked for ahead")
            assertTrue(s.images.demanded.size <= 3, "${s.images.demanded.size} photos rendered on demand")
        }

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

    /** Spec `slideshow-display`: Skip faulty files › Out of memory while rendering. */
    @Test
    fun `Out of memory while rendering skips the photo and the slideshow goes on`() =
        runTest {
            val images = FakeImages().apply { outOfMemory += photos[0].id }
            val s = Setup(this, photos, allReady = false)
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

            // The command loop still runs: a swipe reaches the next photo.
            images.readyIds += photos[2].id
            controller.next()
            runCurrent()
            assertEquals(photos[2], (controller.state.value as SlideState.Showing).photo)
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
            val evening = s.controller.state.value
            s.controller.setResting(false)
            runCurrent()
            assertEquals(photos[0], s.shown(), "the photo from the evening")
            assertTrue(s.controller.state.value === evening, "shown again as it was, without a new slide")
            advanceTimeBy(29_999)
            runCurrent()
            assertEquals(photos[0], s.shown())
            advanceTimeBy(2)
            runCurrent()
            assertEquals(photos[1], s.shown(), "the next photo after one full interval")
        }

    @Test
    fun `Off and on by hand`() =
        runTest {
            val s = Setup(this, photos)
            advanceTimeBy(25_000)
            s.controller.setResting(true)
            runCurrent()
            s.images.prefetched.clear()
            advanceTimeBy(60_000)
            s.controller.setResting(false)
            runCurrent()
            assertEquals(photos[0], s.shown(), "the same photo as before")
            assertEquals(photos[1].id to RenderPriority.NEXT, s.images.prefetched.first(), "the upcoming photos are rendered again")
            advanceTimeBy(29_999)
            runCurrent()
            assertEquals(photos[0], s.shown(), "not the 5 s left from before")
            advanceTimeBy(2)
            runCurrent()
            assertEquals(photos[1], s.shown())
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
    fun `Photo deleted at night`() =
        runTest {
            val s = Setup(this, photos)
            s.controller.setResting(true)
            runCurrent()
            s.library -= photos[0].id
            s.visible.value = photos - photos[0]
            runCurrent()
            advanceTimeBy(9 * 3600_000L)
            s.controller.setResting(false)
            runCurrent()
            assertEquals(photos[1], s.shown(), "the next visible photo at once")
            advanceTimeBy(29_999)
            runCurrent()
            assertEquals(photos[1], s.shown())
            advanceTimeBy(2)
            runCurrent()
            assertEquals(photos[2], s.shown(), "the interval starts over")
        }

    @Test
    fun `Photo hidden at night`() =
        runTest {
            val s = Setup(this, photos)
            s.controller.setResting(true)
            runCurrent()
            // Hidden, not deleted: it stays in the library but leaves the visible photos.
            s.visible.value = photos - photos[0]
            runCurrent()
            assertEquals(photos[0], s.shown(), "not while the display is off")
            s.controller.setResting(false)
            runCurrent()
            assertEquals(photos[1], s.shown(), "the next visible photo at once")
        }

    // Spec `slideshow-display` › Current part of the photo on screen.

    private val head = Crop(0.5, 0.2, 1.0)

    /** The library with [photo] changed, as a new section of it arrives. */
    private fun Setup.change(photo: PhotoEntry) {
        visible.value = visible.value.map { if (it.id == photo.id) photo else it }
    }

    private fun Setup.file(): File = (controller.state.value as SlideState.Showing).file

    @Test
    fun `Section of the photo on screen`() =
        runTest {
            val s = Setup(this, photos)
            val before = s.file()
            advanceTimeBy(10_000)
            s.change(photos[0].copy(crop = head))
            runCurrent()
            assertEquals(before, s.file(), "the older part until the new one is rendered")
            assertEquals(listOf(photos[0].id to RenderPriority.NEXT), s.images.priorities)
            s.images.pending
                .getValue(photos[0].id)
                .complete(File("p01-head.jpg"))
            runCurrent()
            val after = s.controller.state.value as SlideState.Showing
            assertEquals(File("p01-head.jpg"), after.file, "the new section once it is rendered")
            assertEquals(head, after.photo.crop)
            assertEquals(0, after.step)
            advanceTimeBy(19_999)
            runCurrent()
            assertEquals(photos[0].id, s.shown()?.id, "the interval that was running goes on")
            advanceTimeBy(2)
            runCurrent()
            assertEquals(photos[1], s.shown(), "after 30 seconds from the start, not from the switch")
        }

    @Test
    fun `Back with previous`() =
        runTest {
            val s = Setup(this, photos)
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(photos[1], s.shown())
            val old = s.images.ready(photos[0])
            s.change(photos[0].copy(crop = head))
            runCurrent()
            assertEquals(emptyList<String>(), s.images.demanded, "the photo on screen did not change")
            s.controller.previous()
            runCurrent()
            assertEquals(photos[0].id, s.shown()?.id)
            assertEquals(old, s.file(), "at once with the older part")
            assertTrue(photos[0].id in s.images.demanded, "and its current part is rendered")
            s.images.pending
                .getValue(photos[0].id)
                .complete(File("p01-head.jpg"))
            runCurrent()
            assertEquals(File("p01-head.jpg"), s.file(), "then the new section, not the old part for good")
        }

    @Test
    fun `Section removed on screen`() =
        runTest {
            val withHead = listOf(photos[0].copy(crop = head)) + photos.drop(1)
            val s = Setup(this, withHead)
            s.change(photos[0])
            runCurrent()
            s.images.pending
                .getValue(photos[0].id)
                .complete(File("p01-auto.jpg"))
            runCurrent()
            assertEquals(File("p01-auto.jpg"), s.file())
            assertEquals(null, s.shown()?.crop, "the fill mode applies again")
        }

    @Test
    fun `Next photo before the switch`() =
        runTest {
            val s = Setup(this, photos)
            s.change(photos[0].copy(crop = head))
            runCurrent()
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(photos[1], s.shown())
            s.images.pending
                .getValue(photos[0].id)
                .complete(File("p01-head.jpg"))
            runCurrent()
            assertEquals(photos[1], s.shown(), "the next photo stays")
            assertTrue(s.images.current(photos[0].copy(crop = head)) != null, "rendered for its next appearance")
        }

    @Test
    fun `A failed render of the current part keeps the older one`() =
        runTest {
            val s = Setup(this, photos)
            val before = s.file()
            s.images.broken += photos[0].id
            s.change(photos[0].copy(crop = head))
            runCurrent()
            assertEquals(before, s.file())
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(photos[1], s.shown(), "the slideshow goes on")
        }

    @Test
    fun `A comment does not render the photo on screen again`() =
        runTest {
            val s = Setup(this, photos)
            s.change(photos[0].copy(comment = "Summer"))
            runCurrent()
            assertEquals(emptyList<String>(), s.images.demanded)
            assertEquals("Summer", s.shown()?.comment, "the entry on screen follows the library")
        }

    @Test
    fun `Orientation changed at night`() =
        runTest {
            val s = Setup(this, photos)
            s.controller.setResting(true)
            runCurrent()
            s.images.variant = "-portrait"
            s.settings.value = s.settings.value.copy(orientation = Orientation.PORTRAIT)
            runCurrent()
            assertEquals(emptyList<String>(), s.images.demanded, "nothing rendered while the display is off")
            s.controller.setResting(false)
            runCurrent()
            assertEquals(listOf(photos[0].id), s.images.demanded, "the photo that stays is rendered for portrait")
            s.images.pending
                .getValue(photos[0].id)
                .complete(File(photos[0].path + "-portrait"))
            runCurrent()
            val after = s.controller.state.value as SlideState.Showing
            assertEquals(photos[0], after.photo, "the same photo")
            assertEquals(File(photos[0].path + "-portrait"), after.file, "laid out for portrait")
            advanceTimeBy(29_999)
            runCurrent()
            assertEquals(photos[0], s.shown())
            advanceTimeBy(2)
            runCurrent()
            assertEquals(photos[1], s.shown())
        }

    @Test
    fun `A presentation change at night also reaches a paused photo`() =
        runTest {
            val s = Setup(this, photos)
            s.controller.setPaused(true)
            s.controller.setResting(true)
            runCurrent()
            s.images.variant = "-fit"
            s.settings.value = s.settings.value.copy(fillMode = FillMode.FIT)
            runCurrent()
            s.controller.setResting(false)
            runCurrent()
            s.images.pending
                .getValue(photos[0].id)
                .complete(File(photos[0].path + "-fit"))
            runCurrent()
            assertEquals(File(photos[0].path + "-fit"), (s.controller.state.value as SlideState.Showing).file)
            assertTrue(s.controller.paused.value, "still paused")
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

    // Spec `slideshow-display` › Operating the slideshow through the API: control() returns once the command took effect.

    @Test
    fun `Next photo from afar returns with the new photo and restarts the interval`() =
        runTest {
            val s = Setup(this, photos)
            advanceTimeBy(20_000)
            s.controller.control(SlideshowAction.NEXT)
            assertEquals(photos[1], s.shown(), "already shown when control returns")
            assertEquals(+1, (s.controller.state.value as SlideState.Showing).step)
            advanceTimeBy(29_999)
            runCurrent()
            assertEquals(photos[1], s.shown(), "the interval starts over")
            advanceTimeBy(2)
            runCurrent()
            assertEquals(photos[2], s.shown())
            s.controller.control(SlideshowAction.PREVIOUS)
            assertEquals(photos[1], s.shown())
        }

    @Test
    fun `Pause and resume from afar return with the new pause state`() =
        runTest {
            val s = Setup(this, photos)
            s.controller.control(SlideshowAction.PAUSE)
            assertTrue(s.controller.paused.value)
            s.controller.control(SlideshowAction.PAUSE)
            assertTrue(s.controller.paused.value, "pause twice stays paused")
            advanceTimeBy(10 * 60_000L)
            runCurrent()
            assertEquals(photos[0], s.shown())
            s.controller.control(SlideshowAction.RESUME)
            assertFalse(s.controller.paused.value)
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(photos[1], s.shown())
        }

    @Test
    fun `Resume from afar while running keeps the interval`() =
        runTest {
            val s = Setup(this, photos)
            advanceTimeBy(25_000)
            s.controller.control(SlideshowAction.RESUME)
            advanceTimeBy(5_001)
            runCurrent()
            assertEquals(photos[1], s.shown(), "the change comes after 30 seconds, not 55")
        }

    @Test
    fun `A step from afar while resting returns with the photo unchanged`() =
        runTest {
            val s = Setup(this, photos)
            s.controller.setResting(true)
            s.controller.control(SlideshowAction.NEXT)
            assertTrue(s.controller.resting.value)
            assertEquals(photos[0], s.shown())
            s.controller.control(SlideshowAction.PAUSE)
            s.controller.setResting(false)
            runCurrent()
            assertEquals(photos[0], s.shown(), "paused at night: the paused photo stays in the morning")
        }

    @Test
    fun `control returns also when handling the command fails`() =
        runTest {
            val s = Setup(this, photos)
            s.images.failing = true
            s.controller.control(SlideshowAction.NEXT)
            s.images.failing = false
            s.controller.control(SlideshowAction.NEXT)
            assertTrue(s.shown() != photos[0], "the slideshow goes on after the error")
        }
}
