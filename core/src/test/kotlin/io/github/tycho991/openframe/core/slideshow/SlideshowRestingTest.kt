package io.github.tycho991.openframe.core.slideshow

import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.PixelSize
import io.github.tycho991.openframe.core.library.RenderCache
import io.github.tycho991.openframe.core.library.RenderSize
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.MediaEngine
import io.github.tycho991.openframe.core.platform.Region
import io.github.tycho991.openframe.core.settings.FillMode
import io.github.tycho991.openframe.core.settings.Order
import io.github.tycho991.openframe.core.settings.Settings
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import io.github.tycho991.openframe.core.testing.TestClock
import io.github.tycho991.openframe.core.testing.waitFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch

/**
 * Spec `display-schedule`: Slideshow rests while the display is off › Renderings queued before the night, with the real
 * render cache: renderings on demand and the refresh of the photo on screen that nobody awaits any more are dropped.
 */
class SlideshowRestingTest {
    @TempDir
    lateinit var dir: File

    private val jvm = JvmMediaEngine()
    private val hold = CountDownLatch(1)
    private val rendered = Collections.synchronizedList(ArrayList<String>())

    /** Holds the render of `busy.jpg` until [hold] opens, so that every other rendering waits behind it. */
    private val engine =
        object : MediaEngine by jvm {
            override fun render(
                source: File,
                target: File,
                width: Int,
                height: Int,
                region: Region,
                quality: Int,
            ) {
                if (source.name == "busy.jpg") hold.await()
                rendered += source.name
                jvm.render(source, target, width, height, region, quality)
            }
        }
    private var fillMode = FillMode.FILL
    private val cache by lazy {
        RenderCache(
            File(dir, "cache"),
            engine,
            { File(dir, it.path) },
            { PixelSize(320, 200) },
            { fillMode },
            { 100L shl 20 },
            AppLog(TestClock()),
        )
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun close() {
        hold.countDown()
        scope.cancel()
        cache.close()
    }

    private fun entry(name: String): PhotoEntry {
        Fixtures.copy("landscape.jpg", File(dir, name))
        return PhotoEntry(PhotoEntry.idFor("local", name), "local", name, 400, 300, name.hashCode().toLong(), 1, 1, 1)
    }

    /** The worker renders `busy.jpg` until [hold] opens. */
    private fun busy() {
        val busy = entry("busy.jpg")
        scope.launch { runCatching { cache.get(busy, RenderSize.THUMB) } }
        waitFor(message = "the worker busy") { cache.queued == 0 && rendered.isEmpty() }
        Thread.sleep(100)
    }

    @Test
    fun `Resting drops the renderings on demand that waited`() {
        val photos = (1..5).map { entry("p$it.jpg") }
        busy()
        val settings = MutableStateFlow(Settings.defaults("x").copy(order = Order.CHRONOLOGICAL))
        val controller =
            SlideshowController(MutableStateFlow(photos), { true }, settings, CachedDisplayImages(cache), scope, AppLog(TestClock()))
        controller.start()
        waitFor(message = "the first rendering on demand queued") { cache.queued > 0 }
        controller.setResting(true)
        waitFor { controller.resting.value }
        Thread.sleep(200)
        hold.countDown()
        Thread.sleep(500)
        assertEquals(listOf("busy.jpg"), rendered.toList(), "nothing rendered for the display while it is off")
        assertEquals(SlideState.Loading, controller.state.value)
    }

    @Test
    fun `Resting drops the refresh of the photo on screen`() {
        val photos = (1..3).map { entry("p$it.jpg") }
        runBlocking { photos.forEach { cache.get(it, RenderSize.DISPLAY) } }
        rendered.clear()
        val settings = MutableStateFlow(Settings.defaults("x").copy(order = Order.CHRONOLOGICAL, fillMode = FillMode.FILL))
        val controller =
            SlideshowController(MutableStateFlow(photos), { true }, settings, CachedDisplayImages(cache), scope, AppLog(TestClock()))
        controller.start()
        waitFor { controller.state.value is SlideState.Showing }
        busy()
        // A new fill mode: the photo on screen is to be rendered again, behind the busy worker.
        fillMode = FillMode.FIT
        settings.value = settings.value.copy(fillMode = FillMode.FIT)
        waitFor(message = "the refresh queued") { cache.queued > 0 }
        controller.setResting(true)
        waitFor { controller.resting.value }
        Thread.sleep(200)
        hold.countDown()
        Thread.sleep(500)
        assertEquals(listOf("busy.jpg"), rendered.toList(), "the photo on screen is not rendered again at night")
        val shown = (controller.state.value as SlideState.Showing).photo
        assertNull(cache.peek(shown, RenderSize.DISPLAY), "its new part waits for the morning")
        controller.setResting(false)
        waitFor(message = "the refresh in the morning") { cache.peek(shown, RenderSize.DISPLAY) != null }
    }
}
