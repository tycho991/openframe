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
import org.junit.jupiter.api.Assertions.assertFalse
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

    private companion object {
        const val RESERVE = 600L * 1024 * 1024
    }

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

    /** A volume of [capacity] bytes that holds only the render folder: the free storage falls with every rendering. */
    private inner class Volume(
        var capacity: Long,
    ) {
        val folder = File(dir, "cache")

        fun free(): Long = capacity - folder.listFiles().orEmpty().sumOf { it.length() }

        fun cache(clock: TestClock = TestClock()) =
            RenderCache(
                folder,
                engine,
                { File(photos, it.path) },
                { screen },
                { fillMode },
                { budget },
                AppLog(TestClock()),
                freeBytes = { free() },
                reserveBytes = RESERVE,
                clock = clock,
            )
    }

    /**
     * Spec `photo-library`: Storage reserve › Storage runs short; Going below the storage reserve (design decision 2 of
     * `library-performance`).
     */
    @Test
    fun `Below the reserve the oldest renderings go, protected ones stay, and only an awaited job renders`() {
        val volume = Volume(Long.MAX_VALUE / 2)
        val c = volume.cache()
        try {
            val (a, b, k, d, e, f) = listOf("a", "b", "k", "d", "e", "f").map { entry("$it.jpg") }
            val g = entry("g.jpg")
            val h = entry("h.jpg")
            runBlocking {
                for (photo in listOf(a, b, d, e, f)) c.get(photo, RenderSize.THUMB)
                // A display size of the window, rendered before the storage ran short.
                c.get(k, RenderSize.DISPLAY)
            }
            // Read from the folder: a peek would count as a use.
            val thumb = File(volume.folder, c.target(b, RenderSize.THUMB).name).length()
            val shown = File(volume.folder, c.target(a, RenderSize.THUMB).name)
            // The photo on screen (the oldest file) and the window are protected.
            c.prefetchAhead(listOf(k), emptyList(), listOf(shown))
            volume.capacity = volume.capacity - volume.free() + RESERVE - 2 * thumb - 1
            assertTrue(runBlocking { c.get(g, RenderSize.THUMB) }.isFile, "an awaited job renders below the reserve")
            assertNotNull(c.peek(a, RenderSize.THUMB), "on screen")
            assertNotNull(c.peek(k, RenderSize.DISPLAY), "in the window")
            assertNull(c.peek(b, RenderSize.THUMB), "unused longest")
            assertNull(c.peek(d, RenderSize.THUMB))
            assertNull(c.peek(e, RenderSize.THUMB), "until the reserve is free again")
            assertNotNull(c.peek(f, RenderSize.THUMB), "not more than that")
            // A job only for the window ahead is not rendered while removing every unprotected rendering does not free
            // the reserve.
            volume.capacity = volume.capacity - volume.free() + RESERVE - 10L * 1024 * 1024
            val renders = engine.renders.get()
            c.prefetchAhead(listOf(h), emptyList(), listOf(shown))
            waitUntilIdle(c)
            assertNull(c.peek(h, RenderSize.DISPLAY))
            assertEquals(renders, engine.renders.get())
            assertNotNull(c.peek(a, RenderSize.THUMB), "the photo on screen still stays")
            assertEquals(listOf(shown.name), volume.folder.list()!!.toList(), "every other rendering removed")
        } finally {
            c.close()
        }
    }

    private operator fun <T> List<T>.component6() = this[5]

    private fun waitUntilIdle(c: RenderCache) {
        val deadline = System.currentTimeMillis() + 5000
        while (c.queued > 0 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        Thread.sleep(200)
    }

    /** Design decision 2 of `library-performance`: Android's trimming leaves the index out of date. */
    @Test
    fun `A file deleted behind the cache's back rebuilds the index once an hour at most`() =
        runBlocking {
            val clock = TestClock()
            val (a, b, x) = listOf("a", "b", "x").map { entry("$it.jpg") }
            val first = Counted(clock = clock)
            listOf(a, b, x).forEach { first.cache.get(it, RenderSize.THUMB) }
            assertEquals(1, first.listings)
            first.cache.peek(a, RenderSize.THUMB)!!.delete()
            assertNull(first.cache.peek(a, RenderSize.THUMB))
            assertEquals(2, first.listings, "one listing at the miss")
            first.cache.peek(b, RenderSize.THUMB)!!.delete()
            assertNull(first.cache.peek(b, RenderSize.THUMB))
            assertNull(first.cache.peek(a, RenderSize.THUMB))
            assertEquals(2, first.listings, "none at the following misses within the hour")
            assertEquals(File(dir, "cache").listFiles()!!.sumOf { it.length() }, first.cache.totalBytes())
            clock.advance(java.time.Duration.ofMinutes(61))
            first.cache.peek(x, RenderSize.THUMB)!!.delete()
            assertNull(first.cache.peek(x, RenderSize.THUMB))
            assertEquals(3, first.listings, "again after an hour")
            first.cache.close()
        }

    /** Design decision 2 of `library-performance`: the order of use survives a restart, and Android trims old files first. */
    @Test
    fun `A rendering's file time is set at most once an hour`() =
        runBlocking {
            val clock = TestClock()
            val c = Counted(clock = clock).cache
            val a = entry("a.jpg")
            val file = c.get(a, RenderSize.THUMB)
            val created = file.lastModified()
            c.peek(a, RenderSize.THUMB)
            clock.advance(java.time.Duration.ofMinutes(30))
            c.peek(a, RenderSize.THUMB)
            assertEquals(created, file.lastModified(), "within the hour of the rendering")
            clock.advance(java.time.Duration.ofMinutes(31))
            c.peek(a, RenderSize.THUMB)
            val set = file.lastModified()
            assertEquals(clock.millis() / 1000, set / 1000, "set to the time of use")
            clock.advance(java.time.Duration.ofMinutes(10))
            c.peekAnyDisplay(a)
            c.peek(a, RenderSize.THUMB)
            assertEquals(set, file.lastModified(), "not again within the hour")
            c.close()
        }

    /** A cache whose worker is held on a first rendering until [Holding.release] opens. */
    private fun held(): Pair<RenderCache, Holding> {
        val holding = Holding()
        val c =
            RenderCache(File(dir, "cache"), holding, { File(photos, it.path) }, { screen }, { fillMode }, { budget }, AppLog(TestClock()))
        val first = entry("first.jpg")
        Thread { runBlocking { runCatching { c.get(first, RenderSize.THUMB) } } }.start()
        assertTrue(holding.started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        return c to holding
    }

    /** Spec `slideshow-display`: Bounded rendering ahead › Window moves on (design decision 6 of `library-performance`). */
    @Test
    fun `Waiting renderings that left the window do not run, and the window can ask again`() {
        val (c, holding) = held()
        try {
            val first = (0 until 10).map { entry("a$it.jpg") }
            val second = (0 until 10).map { entry("k$it.jpg") }
            c.prefetchAhead(first.take(2), first.drop(2), emptyList())
            c.prefetchAhead(second.take(2), second.drop(2), emptyList())
            holding.release.countDown()
            waitUntilIdle(c)
            assertTrue(first.none { c.peek(it, RenderSize.DISPLAY) != null }, "none of the first window rendered")
            assertTrue(second.all { c.peek(it, RenderSize.DISPLAY) != null }, "the second window rendered")
            c.prefetchAhead(first.take(1), emptyList(), emptyList())
            waitUntilIdle(c)
            assertNotNull(c.peek(first[0], RenderSize.DISPLAY), "asked for again, it is queued again")
        } finally {
            holding.release.countDown()
            c.close()
        }
    }

    @Test
    fun `An awaited rendering is never dropped, at DEMAND or at NEXT`() {
        val (c, holding) = held()
        try {
            val raised = entry("raised.jpg")
            val refresh = entry("refresh.jpg")
            c.prefetchAhead(listOf(raised), emptyList(), emptyList())
            runBlocking {
                // A request raises the window's job to DEMAND; the refresh of the photo on screen awaits one at NEXT.
                val demanded = async(kotlinx.coroutines.Dispatchers.IO) { c.get(raised, RenderSize.DISPLAY) }
                val next = async(kotlinx.coroutines.Dispatchers.IO) { c.get(refresh, RenderSize.DISPLAY, RenderPriority.NEXT) }
                while (c.queued < 3) Thread.sleep(5)
                c.prefetchAhead(emptyList(), emptyList(), emptyList())
                holding.release.countDown()
                assertTrue(kotlinx.coroutines.withTimeout(10_000) { demanded.await() }.isFile)
                assertTrue(kotlinx.coroutines.withTimeout(10_000) { next.await() }.isFile)
            }
        } finally {
            holding.release.countDown()
            c.close()
        }
    }

    /**
     * Design decision 6a of `library-performance`: the worker quarantines a photo (an index edit) while another thread's
     * edit removes photos (the cache lock inside the index lock); both finish.
     */
    @Test
    fun `Quarantine from the worker and a removal from an edit do not deadlock`() {
        val crash = java.util.concurrent.CountDownLatch(1)
        val crashing =
            object : io.github.tycho991.openframe.core.platform.MediaEngine by engine {
                override fun render(
                    source: File,
                    target: File,
                    width: Int,
                    height: Int,
                    region: Region,
                    quality: Int,
                ) {
                    crash.await()
                    throw io.github.tycho991.openframe.core.platform.ImageException(
                        io.github.tycho991.openframe.core.platform.SkipReason.CRASHED,
                        "The decoder process died",
                    )
                }
            }
        lateinit var c: RenderCache
        val index =
            PhotoIndexStore(
                io.github.tycho991.openframe.core.store.JsonStore(
                    File(dir, "index.json"),
                    StoredIndex.serializer(),
                    kotlinx.coroutines.test.TestScope(),
                    AppLog(TestClock()),
                ),
            ) { removed -> c.removeAll(removed) }
        index.load()
        c =
            RenderCache(
                File(dir, "cache"),
                crashing,
                { File(photos, it.path) },
                { screen },
                { fillMode },
                { budget },
                AppLog(TestClock()),
                onCrashed = { e -> index.edit { it.get(e.id)?.let { cur -> it.put(cur.copy(quarantinedVersion = e.version)) } } },
            )
        try {
            val bad = entry("bad.jpg")
            val others = (0 until 50).map { entry("o$it.jpg") }
            index.edit { edit -> (others + bad).forEach(edit::put) }
            val rendering = Thread { runBlocking { runCatching { c.get(bad, RenderSize.THUMB) } } }.apply { start() }
            val removing =
                Thread {
                    index.edit { edit ->
                        crash.countDown()
                        // The worker reaches its quarantine while this edit holds the index lock.
                        Thread.sleep(200)
                        others.forEach { edit.remove(it.id) }
                    }
                }.apply { start() }
            rendering.join(10_000)
            removing.join(10_000)
            assertFalse(rendering.isAlive || removing.isAlive, "both finished")
            assertEquals(
                bad.version,
                index.library.value.photos
                    .getValue(bad.id)
                    .quarantinedVersion,
            )
        } finally {
            crash.countDown()
            c.close()
        }
    }

    /** A late job of an older version neither removes the newer one nor stays (review of `library-performance`). */
    @Test
    fun `A late rendering of an older version keeps the newer one`() =
        runBlocking {
            val c = cache()
            val v1 = entry("a.jpg")
            val v2 = v1.copy(version = 2)
            val newer = c.get(v2, RenderSize.THUMB)
            val late = runCatching { c.get(v1, RenderSize.THUMB) }
            assertTrue(late.exceptionOrNull() is RenderDroppedException, "${late.exceptionOrNull()}")
            assertTrue(newer.isFile, "the newer version stays")
            assertEquals(listOf(newer.name), File(dir, "cache").list()!!.toList())
            c.close()
        }

    @Test
    fun `A new version keeps an older file that is on screen`() =
        runBlocking {
            val c = cache()
            val v1 = entry("a.jpg")
            val shown = c.get(v1, RenderSize.DISPLAY)
            c.prefetchAhead(emptyList(), emptyList(), listOf(shown))
            c.get(v1.copy(version = 2), RenderSize.DISPLAY)
            assertTrue(shown.isFile, "the display may be decoding it")
            c.close()
        }

    /** A rebuild must not take back files the delete thread has not reached yet (review of `library-performance`). */
    @Test
    fun `A rebuild does not take back files that wait to be deleted`() =
        runBlocking {
            val clock = TestClock()
            val (a, b) = listOf("a", "b").map { entry("$it.jpg") }
            val counted = Counted(clock = clock)
            val c = counted.cache
            c.get(a, RenderSize.THUMB)
            val bFile = c.get(b, RenderSize.THUMB)
            val hold = java.util.concurrent.CountDownLatch(1)
            c.beforeDelete = { hold.await() }
            c.removeAll(listOf(a))
            // Android trims b; the next peek lists the folder while a's file still waits for the delete thread.
            bFile.delete()
            assertNull(c.peek(b, RenderSize.THUMB))
            assertEquals(2, counted.listings, "the rebuild listed the folder")
            assertNull(c.peek(a, RenderSize.THUMB), "a left the library")
            assertEquals(0L, c.totalBytes(), "neither a nor b counts")
            hold.countDown()
            c.awaitDeletions()
            assertEquals(emptyList<String>(), File(dir, "cache").list()!!.toList())
            c.close()
        }

    /** Review of `library-performance`: a rendering that cannot be renamed into place leaves no `.tmp` file. */
    @Test
    fun `A failed rename deletes its temporary file`() {
        val c = cache()
        try {
            val e = entry("a.jpg")
            // A folder in the way of the target: the rename fails.
            File(File(dir, "cache"), c.target(e, RenderSize.THUMB).name).apply { mkdirs() }.resolve("x").writeBytes(ByteArray(1))
            val result = runCatching { runBlocking { c.get(e, RenderSize.THUMB) } }
            assertTrue(result.isFailure)
            assertEquals(emptyList<String>(), File(dir, "cache").list()!!.filter { it.endsWith(".tmp") })
        } finally {
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
