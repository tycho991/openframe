package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.Region
import io.github.tycho991.openframe.core.settings.FillMode
import io.github.tycho991.openframe.core.settings.Orientation
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import io.github.tycho991.openframe.core.testing.TestClock
import io.github.tycho991.openframe.core.testing.TestFrame
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
            // 400×300 on 320×200 at zoom 2 (scale 1.6): half the width and 200/480 of the height, enlarged by the display.
            assertRegion(Region(0.0, 0.0, 0.5, 200.0 / 480), engine.regions.last())
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
            c.prefetchAhead(emptyList(), entries.take(1), emptyList())
            // Wait until the worker holds the first job.
            val deadline = System.currentTimeMillis() + 5000
            while (c.queued > 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            repeat(20) { c.prefetchAhead(emptyList(), entries.drop(1), emptyList()) }
            assertEquals(3, c.queued, "each waiting file once")
            c.prefetchAhead(entries.drop(1), emptyList(), emptyList())
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
            c.removeAll(listOf(e))
            c.awaitDeletions()
            assertEquals(0, File(dir, "cache").list()!!.size)
        }

    @Test
    fun `Removing many photos at once lists nothing and deletes exactly their renderings`() {
        val folder = CountingDir(File(dir, "cache"))
        folder.mkdirs()
        // IDs contain '-', and one ID is a prefix of another: only the renderings of the removed IDs may go.
        val ids = (0 until 300).map { PhotoEntry.idFor("source-$it", "photo.jpg") }
        val prefixed = ids.first().dropLast(1)
        val longer = ids.first() + "0"
        for (id in ids + prefixed + longer) {
            File(folder, "$id-v1-400x300.jpg").writeBytes(ByteArray(1))
            File(folder, "$id-v2-pr1-abc.jpg").writeBytes(ByteArray(1))
        }
        val c =
            RenderCache(folder, engine, { File(photos, it.path) }, { screen }, { fillMode }, { budget }, AppLog(TestClock()))
        try {
            val removed = ids.filterIndexed { i, _ -> i % 2 == 0 }
            val listings = folder.listings
            c.removeAll(removed.map { photo(it, version = 2) })
            c.awaitDeletions()
            assertEquals(listings, folder.listings, "no listing")
            val left = folder.list()!!.toSet()
            val expected = (ids - removed.toSet() + prefixed + longer).flatMap { listOf("$it-v1-400x300.jpg", "$it-v2-pr1-abc.jpg") }
            assertEquals(expected.toSet(), left)
            assertEquals(expected.size.toLong(), c.totalBytes(), "the running total follows")

            c.removeAll(emptyList())
            c.removeAll(listOf(photo(prefixed, version = 2)))
            c.awaitDeletions()
            assertEquals(listings, folder.listings, "still no listing")
            assertEquals(left - setOf("$prefixed-v1-400x300.jpg", "$prefixed-v2-pr1-abc.jpg"), folder.list()!!.toSet())
        } finally {
            c.close()
        }
    }

    private fun photo(
        id: String,
        version: Int = 1,
    ) = PhotoEntry(id, id.substringBefore('-'), "photo.jpg", 400, 300, 0, 1, 1, version)

    /** Design decision 13 of `library-performance`: rendering does not list the folder, nor does removing a source. */
    @Test
    fun `Rendering 1,000 photos and removing their source lists the folder only a few times`() {
        var listings = 0
        // Writes a file instead of decoding, so that 1,000 renderings take no time; the cache's own work is counted.
        val writing =
            object : io.github.tycho991.openframe.core.platform.MediaEngine by engine {
                override fun render(
                    source: File,
                    target: File,
                    width: Int,
                    height: Int,
                    region: Region,
                    quality: Int,
                ) = target.writeBytes(ByteArray(10))
            }
        val c =
            RenderCache(
                File(dir, "cache"),
                writing,
                { File(photos, it.path) },
                { screen },
                { fillMode },
                { budget },
                AppLog(TestClock()),
                listFolder = {
                    listings++
                    it.listFiles()
                },
            )
        try {
            val entries = (0 until 1000).map { PhotoEntry(PhotoEntry.idFor("s1", "p$it.jpg"), "s1", "p$it.jpg", 400, 300, 0, 1, 1, 1) }
            runBlocking { entries.forEach { c.get(it, RenderSize.THUMB) } }
            c.removeAll(entries)
            assertTrue(listings <= 3, "listed $listings times")
        } finally {
            c.close()
        }
    }

    /** A cache whose listings and size reads are counted, for the cases of design decision 3 of `library-performance`. */
    private inner class Counted(
        engine: io.github.tycho991.openframe.core.platform.MediaEngine = this.engine,
        clock: TestClock = TestClock(),
    ) {
        var listings = 0
        var sizeReads = 0
        val cache =
            RenderCache(
                File(dir, "cache"),
                engine,
                { File(photos, it.path) },
                { screen },
                { fillMode },
                { budget },
                AppLog(TestClock()),
                listFolder = {
                    listings++
                    it.listFiles()
                },
                sizeOf = {
                    sizeReads++
                    it.length()
                },
                clock = clock,
            )
    }

    /** Spec `photo-library`: Limit storage space, without reading the folder per rendering. */
    @Test
    fun `Rendering below the budget lists nothing and reads only the size of the new file`() =
        runBlocking {
            val first = Counted()
            val entries = (1..5).map { entry("s$it.jpg") }
            entries.take(4).forEach { first.cache.get(it, RenderSize.THUMB) }
            first.cache.close()
            val c = Counted()
            assertEquals(1, c.listings, "one listing at the start")
            assertEquals(4, c.sizeReads, "each file's size once")
            c.cache.get(entries[4], RenderSize.THUMB)
            assertEquals(1, c.listings, "no listing for a rendering")
            assertEquals(5, c.sizeReads, "only the size of the new file")
            assertEquals(File(dir, "cache").listFiles()!!.sumOf { it.length() }, c.cache.totalBytes())
            c.cache.close()
        }

    @Test
    fun `Removing 1,000 of 1,100 photos lists nothing`() {
        val folder = File(dir, "cache").apply { mkdirs() }
        val all = (0 until 1100).map { photo(PhotoEntry.idFor("local", "p$it.jpg")) }
        all.forEach { File(folder, "${it.id}-v1-400x300.jpg").writeBytes(ByteArray(2)) }
        val c = Counted()
        try {
            c.cache.removeAll(all.take(1000))
            c.cache.awaitDeletions()
            assertEquals(1, c.listings, "only the one at the start")
            assertEquals(100, folder.list()!!.size)
            assertEquals(200L, c.cache.totalBytes())
        } finally {
            c.cache.close()
        }
    }

    @Test
    fun `A file whose name is not that of a rendering is deleted at the start`() {
        val folder = File(dir, "cache").apply { mkdirs() }
        File(folder, "junk.jpg").writeBytes(ByteArray(1))
        File(folder, ".${PhotoEntry.idFor("local", "a.jpg")}-v1-400x300.jpg.tmp").writeBytes(ByteArray(1))
        val kept = File(folder, "${PhotoEntry.idFor("local", "a.jpg")}-v1-400x300.jpg").apply { writeBytes(ByteArray(1)) }
        cache().close()
        assertEquals(listOf(kept.name), folder.list()!!.toList())
    }

    @Test
    fun `Photo ID and version of a rendering's name`() {
        val id = PhotoEntry.idFor("s0000beef", "a.jpg")
        assertEquals(id to 12, RenderCache.parse("$id-v12-400x300.jpg"))
        assertEquals(id to 3, RenderCache.parse("$id-v3-pr1-abc.jpg"))
        assertNull(RenderCache.parse("junk.jpg"))
        assertNull(RenderCache.parse("$id-vx-400x300.jpg"))
        assertNull(RenderCache.parse("-v1-a.jpg"))
    }

    /** An engine that holds every render until [release] opens, and tells when one started. */
    private inner class Holding : io.github.tycho991.openframe.core.platform.MediaEngine by engine {
        val release = java.util.concurrent.CountDownLatch(1)
        val started = java.util.concurrent.CountDownLatch(1)

        override fun render(
            source: File,
            target: File,
            width: Int,
            height: Int,
            region: Region,
            quality: Int,
        ) {
            started.countDown()
            release.await()
            engine.render(source, target, width, height, region, quality)
        }
    }

    /** Spec `photo-library`: Rendered sizes follow the index › Removed while rendering. */
    @Test
    fun `A photo removed while one of its sizes renders leaves no file`() {
        val holding = Holding()
        val c =
            RenderCache(File(dir, "cache"), holding, { File(photos, it.path) }, { screen }, { fillMode }, { budget }, AppLog(TestClock()))
        try {
            val e = entry("a.jpg")
            runBlocking {
                val rendering = async(kotlinx.coroutines.Dispatchers.IO) { runCatching { c.get(e, RenderSize.THUMB) } }
                assertTrue(holding.started.await(5, java.util.concurrent.TimeUnit.SECONDS))
                c.removeAll(listOf(e))
                holding.release.countDown()
                val result = rendering.await()
                assertTrue(result.exceptionOrNull() is RenderDroppedException, "${result.exceptionOrNull()}")
            }
            c.awaitDeletions()
            assertEquals(emptyList<String>(), File(dir, "cache").list()!!.toList(), "neither the rendering nor its .tmp")
            assertEquals(0L, c.totalBytes())
            // A new version of the photo, a file at the same path later, renders again.
            assertTrue(runBlocking { c.get(e.copy(version = 5), RenderSize.THUMB) }.isFile)
        } finally {
            holding.release.countDown()
            c.close()
        }
    }

    @Test
    fun `A waiting job of a removed photo neither renders nor fetches its original`() {
        val holding = Holding()
        val fetched = java.util.Collections.synchronizedList(ArrayList<String>())
        val c =
            RenderCache(
                File(dir, "cache"),
                holding,
                { if (it.source == "local") File(photos, it.path) else null },
                { screen },
                { fillMode },
                { budget },
                AppLog(TestClock()),
                fetch = { e, _ ->
                    fetched += e.id
                    File(photos, e.path)
                },
            )
        try {
            val first = entry("first.jpg")
            Fixtures.copy("landscape.jpg", File(photos, "r.jpg"))
            val remote = PhotoEntry(PhotoEntry.idFor("s1", "r.jpg"), "s1", "r.jpg", 400, 300, 0, 1, 1, 3)
            val local = entry("l.jpg")
            runBlocking {
                val busy = async(kotlinx.coroutines.Dispatchers.IO) { c.get(first, RenderSize.THUMB) }
                assertTrue(holding.started.await(5, java.util.concurrent.TimeUnit.SECONDS))
                val waitingRemote = async(kotlinx.coroutines.Dispatchers.IO) { runCatching { c.get(remote, RenderSize.THUMB) } }
                val waitingLocal = async(kotlinx.coroutines.Dispatchers.IO) { runCatching { c.get(local, RenderSize.THUMB) } }
                while (c.queued < 2) Thread.sleep(5)
                c.removeAll(listOf(remote, local.copy(version = 2)))
                holding.release.countDown()
                busy.await()
                assertTrue(waitingRemote.await().exceptionOrNull() is RenderDroppedException)
                assertTrue(waitingLocal.await().exceptionOrNull() is RenderDroppedException, "an older version is dropped as well")
            }
            assertEquals(emptyList<String>(), fetched.toList())
            assertEquals(1, engine.renders.get(), "only the first photo rendered")
        } finally {
            holding.release.countDown()
            c.close()
        }
    }

    /** A folder that counts how often it is listed. */
    private class CountingDir(
        file: File,
    ) : File(file.path) {
        var listings = 0

        override fun listFiles(): Array<File>? {
            listings++
            return super.listFiles()
        }
    }

    @Test
    fun `Error while rendering`() {
        val e = entry("b.jpg", "broken.jpg")
        val result = runCatching { runBlocking { cache().get(e, RenderSize.THUMB) } }
        assertTrue(result.isFailure)
    }

    /** Spec `slideshow-display`: Skip faulty files › Out of memory while rendering. */
    @Test
    fun `Out of memory while rendering fails the request with 503 and the worker goes on`() {
        TestFrame(File(dir, "frame")).use { frame ->
            Fixtures.copy("landscape.jpg", File(frame.photos, "a.jpg"))
            Fixtures.copy("landscape.jpg", File(frame.photos, "b.jpg"))
            frame.scan()
            frame.engine.renderFailure = { if (it.name == "a.jpg") OutOfMemoryError("test") else null }
            val a = PhotoEntry.idFor("local", "a.jpg")
            val b = PhotoEntry.idFor("local", "b.jpg")
            assertEquals(503, frame.get("/api/v1/photos/$a/image?size=full").statusCode())
            assertEquals(200, frame.get("/api/v1/photos/$b/image?size=full").statusCode())
        }
        val e = entry("c.jpg")
        engine.renderFailure = { OutOfMemoryError("test") }
        val result = runCatching { runBlocking { cache().get(e, RenderSize.THUMB) } }
        assertTrue(result.exceptionOrNull() is RenderFailedException, "${result.exceptionOrNull()}")
    }
}
