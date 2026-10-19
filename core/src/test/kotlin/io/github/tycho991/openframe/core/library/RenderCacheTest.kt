package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.log.AppLog
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

    private fun cache() = RenderCache(File(dir, "cache"), engine, { File(photos, it.path) }, { screen }, { budget }, AppLog(TestClock()))

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
    fun `Screen-covering display size`() {
        val landscape = RenderSizes.screen(1920, 1200, Orientation.LANDSCAPE)
        assertEquals(PixelSize(1920, 1200), landscape)
        assertEquals(PixelSize(1200, 1920), RenderSizes.screen(1920, 1200, Orientation.PORTRAIT))
        assertEquals(PixelSize(1200, 1920), RenderSizes.screen(1200, 1920, Orientation.PORTRAIT))
        // Scenario "Portrait photo on a landscape frame".
        assertEquals(PixelSize(1920, 2560), RenderSizes.display(3000, 4000, landscape))
        assertEquals(PixelSize(1920, 1280), RenderSizes.display(6000, 4000, landscape))
        // Panorama 8:1 at the upper limit: at most 2.5 times the screen's pixel count.
        val pano = RenderSizes.display(16000, 2000, landscape)
        assertTrue(pano.width.toLong() * pano.height <= (2.5 * 1920 * 1200).toLong() + pano.width + pano.height)
        assertEquals(8.0, pano.width.toDouble() / pano.height, 0.01)
        // Smaller than the screen: do not scale up.
        assertEquals(PixelSize(800, 600), RenderSizes.display(800, 600, landscape))
        assertEquals(PixelSize(400, 300), RenderSizes.thumb(4000, 3000))
        assertEquals(PixelSize(300, 400), RenderSizes.thumb(3000, 4000))
        assertEquals(PixelSize(200, 100), RenderSizes.thumb(200, 100))
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
            // rotated6: stored 400×300, displayed 300×400.
            val e = entry("r.jpg", "rotated6.jpg", 300, 400)
            screen = PixelSize(200, 120)
            val img = ImageIO.read(cache().get(e, RenderSize.DISPLAY))
            assertEquals(200 to 267, img.width to img.height)
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
