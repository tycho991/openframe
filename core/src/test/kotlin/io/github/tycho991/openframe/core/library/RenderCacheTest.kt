package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.Region
import io.github.tycho991.openframe.core.settings.FillMode
import io.github.tycho991.openframe.core.settings.Orientation
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.imageio.ImageIO

/** Spec `photo-library`: Rendered image sizes, limit storage space. */
class RenderCacheTest {
    @TempDir
    lateinit var dir: File

    private val engine = JvmMediaEngine()
    private val photos get() = File(dir, "photos")
    private var budget = 10L * 1024 * 1024
    private var screen = PixelSize(320, 200)
    private var fillMode = FillMode.FILL

    private fun cache() =
        RenderCache(File(dir, "cache"), engine, { File(photos, it.path) }, { screen }, { fillMode }, { budget }, AppLog(TestClock()))

    private fun entry(
        name: String,
        fixture: String = "landscape.jpg",
        width: Int = 400,
        height: Int = 300,
        version: Int = 1,
    ): PhotoEntry {
        Fixtures.copy(fixture, File(photos, name))
        return PhotoEntry(PhotoEntry.idFor("local", name), "local", name, width, height, 0, 1, 1, version)
    }

    @Test
    fun `Screen, thumbnail and full size`() {
        assertEquals(PixelSize(1920, 1200), RenderSizes.screen(1920, 1200, Orientation.LANDSCAPE))
        assertEquals(PixelSize(1200, 1920), RenderSizes.screen(1920, 1200, Orientation.PORTRAIT))
        assertEquals(PixelSize(1200, 1920), RenderSizes.screen(1200, 1920, Orientation.PORTRAIT))
        assertEquals(PixelSize(400, 300), RenderSizes.thumb(4000, 3000))
        assertEquals(PixelSize(300, 400), RenderSizes.thumb(3000, 4000))
        assertEquals(PixelSize(200, 100), RenderSizes.thumb(200, 100))
        assertEquals(PixelSize(2048, 1536), RenderSizes.full(4000, 3000))
        assertEquals(PixelSize(1536, 2048), RenderSizes.full(6000, 8000))
        // Never larger than the original.
        assertEquals(PixelSize(800, 600), RenderSizes.full(800, 600))
    }

    @Test
    fun `Portrait photo on a landscape frame`() {
        screen = PixelSize(1920, 1200)
        fillMode = FillMode.FILL
        val target = cache().target(PhotoEntry("p", "local", "p.jpg", 3000, 4000, 0, 1, 1, 1), RenderSize.DISPLAY)
        assertEquals(PixelSize(1920, 1200), target.size)
        // The middle 3000×1875 pixels.
        assertRegion(Region(0.0, 1062.5 / 4000, 1.0, 1875.0 / 4000), target.region)
    }

    @Test
    fun `Portrait photo with borders`() {
        screen = PixelSize(1920, 1200)
        fillMode = FillMode.AUTO
        val target = cache().target(PhotoEntry("p", "local", "p.jpg", 3000, 4000, 0, 1, 1, 1), RenderSize.DISPLAY)
        // The borders are not part of the image: 1200×1200 of the middle 3000×3000 pixels.
        assertEquals(PixelSize(1200, 1200), target.size)
        assertRegion(Region(0.0, 0.125, 1.0, 0.75), target.region)
    }

    @Test
    fun `A new section or fill mode gives a new display file, the older one stays available until then`() =
        runBlocking {
            val c = cache()
            val e = entry("a.jpg")
            val first = c.get(e, RenderSize.DISPLAY)
            val withCrop = e.copy(crop = Crop(0.0, 0.0, 2.0))
            assertNull(c.peek(withCrop, RenderSize.DISPLAY), "new section, not rendered yet")
            assertEquals(first, c.peekAnyDisplay(withCrop))
            val cropped = c.get(withCrop, RenderSize.DISPLAY)
            assertTrue(cropped != first)
            // 400×300 on 320×200 at zoom 2 would be 0.4 of the width, but one original pixel per screen pixel limits it to 1.25.
            assertRegion(Region(0.0, 0.0, 0.8, 2.0 / 3), engine.regions.last())
            fillMode = FillMode.FIT
            assertNull(c.peek(e, RenderSize.DISPLAY), "new fill mode, not rendered yet")
            c.get(e, RenderSize.DISPLAY)
            assertEquals(Region.FULL, engine.regions.last())
            assertEquals(3, engine.renders.get())
        }

    @Test
    fun `Full size is uncropped and never taken for the display`() =
        runBlocking {
            val c = cache()
            val e = entry("a.jpg").copy(crop = Crop(0.0, 0.0, 2.0))
            val full = ImageIO.read(c.get(e, RenderSize.FULL))
            assertEquals(400 to 300, full.width to full.height)
            assertEquals(Region.FULL, engine.regions.last())
            c.get(e, RenderSize.THUMB)
            assertNull(c.peekAnyDisplay(e), "neither the thumbnail nor the full size is a display size")
        }

    @Test
    fun `A file asked for again while it waits is queued once`() {
        // The worker is held on the first render, so the others wait in the queue.
        val release = java.util.concurrent.CountDownLatch(1)
        val holding =
            object : io.github.tycho991.openframe.core.platform.MediaEngine by engine {
                override fun render(
                    source: File,
                    target: File,
                    width: Int,
                    height: Int,
                    region: Region,
                    quality: Int,
                ) {
                    release.await()
                    engine.render(source, target, width, height, region, quality)
                }
            }
        val c =
            RenderCache(File(dir, "cache"), holding, { File(photos, it.path) }, { screen }, { fillMode }, { budget }, AppLog(TestClock()))
        try {
            val entries = (1..4).map { entry("q$it.jpg") }
            c.prefetch(entries.take(1))
            // Wait until the worker holds the first job.
            val deadline = System.currentTimeMillis() + 5000
            while (c.queued > 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            repeat(20) { c.prefetch(entries.drop(1)) }
            assertEquals(3, c.queued, "each waiting file once")
            c.prefetch(entries.drop(1), RenderPriority.NEXT)
            assertEquals(6, c.queued, "again once each when asked for more urgently")
        } finally {
            release.countDown()
            c.close()
        }
    }

    private fun assertRegion(
        expected: Region,
        actual: Region,
    ) {
        assertEquals(expected.x, actual.x, 1e-9, "x")
        assertEquals(expected.y, actual.y, 1e-9, "y")
        assertEquals(expected.width, actual.width, 1e-9, "width")
        assertEquals(expected.height, actual.height, 1e-9, "height")
    }

    @Test
    fun `Fetch thumbnail`() =
        runBlocking {
            val e = entry("a.jpg")
            val file = cache().get(e, RenderSize.THUMB)
            val img = ImageIO.read(file)
            assertEquals(400 to 300, img.width to img.height)
        }

    @Test
    fun `Display size respects EXIF and screen`() =
        runBlocking {
            // rotated6: stored 400×300, displayed 300×400; in `fill` the middle 300×180 of it at 200×120.
            val e = entry("r.jpg", "rotated6.jpg", 300, 400)
            screen = PixelSize(200, 120)
            val img = ImageIO.read(cache().get(e, RenderSize.DISPLAY))
            assertEquals(200 to 120, img.width to img.height)
            assertRegion(Region(0.0, 0.275, 1.0, 0.45), engine.regions.single())
        }

    @Test
    fun `Repeated request does not render again`() =
        runBlocking {
            val c = cache()
            val e = entry("a.jpg")
            c.get(e, RenderSize.THUMB)
            c.get(e, RenderSize.THUMB)
            assertEquals(1, engine.renders.get())
            // A new cache after a restart finds the file as well.
            cache().get(e, RenderSize.THUMB)
            assertEquals(1, engine.renders.get())
        }

    @Test
    fun `Concurrent requests render once`() =
        runBlocking {
            val c = cache()
            val e = entry("a.jpg")
            (1..10).map { async { c.get(e, RenderSize.DISPLAY) } }.awaitAll()
            assertEquals(1, engine.renders.get())
        }

    @Test
    fun `New image version replaces old files`() =
        runBlocking {
            val c = cache()
            val v1 = entry("a.jpg")
            c.get(v1, RenderSize.THUMB)
            c.get(v1, RenderSize.DISPLAY)
            val v2 = v1.copy(version = 2)
            c.get(v2, RenderSize.THUMB)
            val names = File(dir, "cache").list()!!.toList()
            assertEquals(listOf("${v1.id}-v2-400x300.jpg"), names)
        }

    @Test
    fun `Budget exceeded`() =
        runBlocking {
            val c = cache()
            val entries = (1..6).map { entry("p$it.jpg") }
            entries.forEach { c.get(it, RenderSize.THUMB) }
            val one = File(dir, "cache").listFiles()!!.first().length()
            budget = one * 3
            c.get(entries[0], RenderSize.THUMB) // last used
            c.trim()
            assertTrue(c.totalBytes() <= budget)
            assertNotNull(c.peek(entries[0], RenderSize.THUMB), "most recently used entry stays")
            assertNull(c.peek(entries[1], RenderSize.THUMB), "entry unused for the longest time is removed")
            assertEquals(6, photos.listFiles()!!.size, "Originals are kept")
        }

    @Test
    fun `Image just rendered stays even with a tiny budget`() =
        runBlocking {
            budget = 1
            val f = cache().get(entry("a.jpg"), RenderSize.THUMB)
            assertTrue(f.exists())
        }

    @Test
    fun `Removing and old display size`() =
        runBlocking {
            val c = cache()
            val e = entry("a.jpg")
            c.get(e, RenderSize.DISPLAY)
            screen = PixelSize(200, 320)
            assertNull(c.peek(e, RenderSize.DISPLAY), "new format, not rendered yet")
            assertNotNull(c.peekAnyDisplay(e), "the old display size is available until then")
            c.remove(e.id)
            assertEquals(0, File(dir, "cache").list()!!.size)
        }

    @Test
    fun `Error while rendering`() {
        val e = entry("b.jpg", "broken.jpg")
        val result = runCatching { runBlocking { cache().get(e, RenderSize.THUMB) } }
        assertTrue(result.isFailure)
    }
}
