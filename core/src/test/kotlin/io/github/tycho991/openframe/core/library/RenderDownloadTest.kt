package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.ImageException
import io.github.tycho991.openframe.core.platform.SkipReason
import io.github.tycho991.openframe.core.settings.FillMode
import io.github.tycho991.openframe.core.sources.SourceException
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Spec `remote-sources`: Rendering does not wait for downloads (design decision 6 of `nextcloud-hardening`). A
 * missing original is fetched on the download thread of the render cache while the worker renders on.
 */
class RenderDownloadTest {
    @TempDir
    lateinit var dir: File

    private val engine = JvmMediaEngine()
    private val photos get() = File(dir, "photos")

    /** The originals that are "on the frame"; a remote one is missing until [fetch] put it there. */
    private val onFrame = Collections.synchronizedMap(HashMap<String, File>())
    private val fetched = Collections.synchronizedList(ArrayList<String>())
    private val released = Collections.synchronizedList(ArrayList<String>())
    private val crashed = Collections.synchronizedList(ArrayList<String>())

    /** Holds every fetch until it is opened. */
    @Volatile private var gate = CountDownLatch(0)

    @Volatile private var fetchFailure: Throwable? = null

    /** Counts down when a fetch begins, before it waits for [gate]. */
    @Volatile private var fetching = CountDownLatch(1)

    /** Whether each fetch was on demand, in order. */
    private val onDemand = Collections.synchronizedList(ArrayList<Boolean>())

    /** The free storage the cache sees, and its reserve; by default far above it. */
    @Volatile private var free = Long.MAX_VALUE / 2
    private val reserve = 600L * 1024 * 1024

    /** Throws for a fetch, after [gate], instead of [fetchFailure] when set; gets whether it is on demand. */
    @Volatile private var failFetch: ((OriginalRequest) -> Throwable?)? = null

    private val cache by lazy {
        RenderCache(
            File(dir, "cache"),
            engine,
            { onFrame[it.id] },
            { PixelSize(320, 200) },
            { FillMode.FILL },
            { 10L * 1024 * 1024 },
            AppLog(TestClock()),
            onCrashed = { crashed += it.id },
            fetch = { entry, request ->
                fetching.countDown()
                gate.await()
                fetched += entry.id
                onDemand += request.onDemand
                failFetch?.invoke(request)?.let { throw it }
                fetchFailure?.let { throw it }
                File(photos, entry.path).also { onFrame[entry.id] = it }
            },
            release = { released += it.id },
            freeBytes = { free },
            reserveBytes = reserve,
        )
    }

    @AfterEach
    fun close() {
        gate.countDown()
        cache.close()
    }

    private fun local(name: String): PhotoEntry {
        Fixtures.copy("landscape.jpg", File(photos, name))
        return PhotoEntry(PhotoEntry.idFor("local", name), "local", name, 400, 300, 0, 1, 1, 1).also { onFrame[it.id] = File(photos, name) }
    }

    /** A remote photo whose original is not on the frame. */
    private fun remote(name: String): PhotoEntry {
        Fixtures.copy("landscape.jpg", File(photos, name))
        return PhotoEntry(PhotoEntry.idFor("s1", name), "s1", name, 400, 300, 0, 1, 1, 1)
    }

    /** The worker took every job; a moment more for it to hand the last one over. */
    private fun handedOver() {
        waitUntil("the hand-over") { cache.queued == 0 }
        Thread.sleep(100)
    }

    private fun waitUntil(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "$what did not happen" }
            Thread.sleep(5)
        }
    }

    @Test
    fun `A slow download does not hold up local photos and photos whose original is there`() {
        gate = CountDownLatch(1)
        val slow = remote("slow.jpg")
        val stored = remote("stored.jpg").also { onFrame[it.id] = File(photos, it.path) }
        val mine = local("mine.jpg")
        runBlocking {
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { cache.get(slow, RenderSize.THUMB) }
            withTimeout(10_000) {
                assertTrue(cache.get(mine, RenderSize.THUMB).isFile, "the local photo renders while the download waits")
                assertTrue(cache.get(stored, RenderSize.DISPLAY).isFile, "and the one with a stored original")
            }
            assertFalse(waiting.isCompleted)
            gate.countDown()
            assertTrue(withTimeout(10_000) { waiting.await() }.isFile)
        }
        assertEquals(listOf(slow.id), fetched)
        waitUntil("the release of the fetched original") { released == listOf(slow.id) }
    }

    @Test
    fun `Two sizes of one photo download its original once`() {
        gate = CountDownLatch(1)
        val photo = remote("p.jpg")
        runBlocking {
            val thumb = async(start = CoroutineStart.UNDISPATCHED) { cache.get(photo, RenderSize.THUMB) }
            val display = async(start = CoroutineStart.UNDISPATCHED) { cache.get(photo, RenderSize.DISPLAY) }
            val full = async(start = CoroutineStart.UNDISPATCHED) { cache.get(photo, RenderSize.FULL) }
            handedOver()
            gate.countDown()
            withTimeout(10_000) {
                assertTrue(thumb.await().isFile && display.await().isFile && full.await().isFile)
            }
        }
        assertEquals(listOf(photo.id), fetched)
        waitUntil("the release after all three") { released == listOf(photo.id) }
    }

    @Test
    fun `A failed download fails every waiting rendering with its own error and quarantines nothing`() {
        gate = CountDownLatch(1)
        fetchFailure = SourceException(SourceException.Kind.UNREACHABLE, "The server cloud.example.org is not reachable")
        val photo = remote("p.jpg")
        runBlocking {
            val thumb = async(start = CoroutineStart.UNDISPATCHED) { runCatching { cache.get(photo, RenderSize.THUMB) } }
            val display = async(start = CoroutineStart.UNDISPATCHED) { runCatching { cache.get(photo, RenderSize.DISPLAY) } }
            handedOver()
            gate.countDown()
            for (result in listOf(thumb.await(), display.await())) {
                assertTrue(result.exceptionOrNull() is SourceException, "${result.exceptionOrNull()}")
            }
        }
        assertEquals(listOf(photo.id), fetched)
        assertEquals(emptyList<String>(), crashed)
        // Not in flight any more: the next request tries again.
        fetchFailure = null
        assertTrue(runBlocking { withTimeout(10_000) { cache.get(photo, RenderSize.THUMB) } }.isFile)
    }

    @Test
    fun `A decoder crash in the check of a fetched original quarantines the photo once`() {
        gate = CountDownLatch(1)
        fetchFailure = ImageException(SkipReason.CRASHED, "The decoder process died")
        val photo = remote("p.jpg")
        runBlocking {
            val thumb = async(start = CoroutineStart.UNDISPATCHED) { runCatching { cache.get(photo, RenderSize.THUMB) } }
            val display = async(start = CoroutineStart.UNDISPATCHED) { runCatching { cache.get(photo, RenderSize.DISPLAY) } }
            handedOver()
            gate.countDown()
            assertTrue(thumb.await().isFailure && display.await().isFailure)
        }
        assertEquals(listOf(photo.id), crashed)
        assertEquals(listOf(photo.id), fetched, "one download for both")
        // Quarantined, the photo is not fetched again for a rendering.
        val quarantined = photo.copy(quarantinedVersion = photo.version)
        assertThrows(RenderFailedException::class.java) { runBlocking { cache.get(quarantined, RenderSize.THUMB) } }
        assertEquals(1, fetched.size)
    }

    @Test
    fun `Downloads follow the priority of what waits for them`() {
        gate = CountDownLatch(1)
        val first = remote("first.jpg")
        val later = remote("later.jpg")
        val urgent = remote("urgent.jpg")
        runBlocking {
            val a = async(start = CoroutineStart.UNDISPATCHED) { cache.get(first, RenderSize.DISPLAY, RenderPriority.PREFETCH) }
            handedOver()
            cache.prefetchAhead(emptyList(), listOf(later), emptyList())
            val c = async(start = CoroutineStart.UNDISPATCHED) { cache.get(urgent, RenderSize.DISPLAY, RenderPriority.DEMAND) }
            handedOver()
            gate.countDown()
            withTimeout(10_000) {
                a.await()
                c.await()
            }
        }
        waitUntil("all three downloads") { fetched.size == 3 }
        assertEquals(listOf(first.id, urgent.id, later.id), fetched.toList())
    }

    /**
     * Spec `photo-library`: Going below the storage reserve › Remote photo without its original below the reserve
     * (design decisions 1 and 2 of `library-performance`).
     */
    @Test
    fun `Below the reserve an awaited rendering downloads on demand and a window job downloads nothing`() {
        free = reserve - 1
        val awaited = remote("a.jpg")
        val ahead = remote("b.jpg")
        cache.prefetchAhead(listOf(ahead), emptyList(), emptyList())
        handedOver()
        assertEquals(emptyList<String>(), fetched.toList(), "no request for the window")
        assertTrue(runBlocking { withTimeout(10_000) { cache.get(awaited, RenderSize.DISPLAY) } }.isFile)
        assertEquals(listOf(awaited.id), fetched.toList())
        assertEquals(listOf(true), onDemand.toList(), "asked for on demand")
    }

    /** Design decision 2 of `library-performance`: a job that arrives later. */
    @Test
    fun `A get that joins a window download failing with storage gets its file through a second download`() {
        gate = CountDownLatch(1)
        val photo = remote("p.jpg")
        failFetch = { request ->
            if (request.onDemand) null else SourceException(SourceException.Kind.STORAGE, "Not enough free storage on the frame")
        }
        cache.prefetchAhead(listOf(photo), emptyList(), emptyList())
        assertTrue(fetching.await(10, TimeUnit.SECONDS), "the window download has started")
        runBlocking {
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { cache.get(photo, RenderSize.DISPLAY) }
            handedOver()
            gate.countDown()
            assertTrue(withTimeout(10_000) { waiting.await() }.isFile)
        }
        assertEquals(listOf(photo.id, photo.id), fetched.toList(), "a second download")
        assertEquals(listOf(false, true), onDemand.toList(), "the second one on demand")
    }

    /** Spec `slideshow-display`: Bounded rendering ahead › Window moves on, for a download that has not started. */
    @Test
    fun `A queued download that left the window makes no request`() {
        gate = CountDownLatch(1)
        val busy = remote("busy.jpg")
        val ahead = remote("ahead.jpg")
        runBlocking {
            // An awaited download holds the download thread; the window's waits behind it.
            val first = async(start = CoroutineStart.UNDISPATCHED) { cache.get(busy, RenderSize.DISPLAY) }
            assertTrue(fetching.await(10, TimeUnit.SECONDS))
            cache.prefetchAhead(listOf(ahead), emptyList(), emptyList())
            handedOver()
            cache.prefetchAhead(emptyList(), emptyList(), emptyList())
            gate.countDown()
            assertTrue(withTimeout(10_000) { first.await() }.isFile)
        }
        Thread.sleep(200)
        assertEquals(listOf(busy.id), fetched.toList())
    }

    @Test
    fun `A download with an awaited job is never cancelled`() {
        gate = CountDownLatch(1)
        val photo = remote("p.jpg")
        lateinit var request: OriginalRequest
        failFetch = {
            request = it
            null
        }
        runBlocking {
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { cache.get(photo, RenderSize.DISPLAY, RenderPriority.NEXT) }
            assertTrue(fetching.await(10, TimeUnit.SECONDS))
            cache.prefetchAhead(emptyList(), emptyList(), emptyList())
            gate.countDown()
            assertTrue(withTimeout(10_000) { waiting.await() }.isFile)
        }
        assertFalse(request.cancelled(), "the refresh of the photo on screen awaits it")
    }

    /**
     * Task 7.2 of `library-performance`: a window job is dropped, a get() for the same name follows, and a duplicate of
     * the dropped job that comes later leaves the new job's entry alone, so a second get() joins it.
     */
    @Test
    fun `A dropped job leaves the entry of a later request for the same file alone`() {
        gate = CountDownLatch(1)
        val busy = remote("busy.jpg")
        val photo = remote("p.jpg")
        runBlocking {
            // An awaited download holds the download thread.
            val first = async(start = CoroutineStart.UNDISPATCHED) { cache.get(busy, RenderSize.THUMB) }
            assertTrue(fetching.await(10, TimeUnit.SECONDS))
            // The window asks for p at PREFETCH and then at NEXT: two jobs for one entry; then it moves on.
            cache.prefetchAhead(emptyList(), listOf(photo), emptyList())
            cache.prefetchAhead(listOf(photo), emptyList(), emptyList())
            cache.prefetchAhead(emptyList(), emptyList(), emptyList())
            handedOver()
            val second = async(start = CoroutineStart.UNDISPATCHED) { cache.get(photo, RenderSize.DISPLAY) }
            handedOver()
            val third = async(start = CoroutineStart.UNDISPATCHED) { cache.get(photo, RenderSize.DISPLAY) }
            handedOver()
            gate.countDown()
            withTimeout(10_000) {
                assertTrue(first.await().isFile)
                assertTrue(second.await().isFile)
                assertEquals(second.await(), third.await())
            }
        }
        assertEquals(listOf(busy.id, photo.id), fetched.toList(), "one download for both requests")
    }

    /** Review of `library-performance`: one of two awaiters gives up; the other still gets the file. */
    @Test
    fun `When one of two awaiters gives up the other gets the file and the download goes on`() {
        gate = CountDownLatch(1)
        val photo = remote("p.jpg")
        lateinit var request: OriginalRequest
        failFetch = {
            request = it
            null
        }
        runBlocking {
            val first = async(start = CoroutineStart.UNDISPATCHED) { cache.get(photo, RenderSize.DISPLAY) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { cache.get(photo, RenderSize.DISPLAY) }
            assertTrue(fetching.await(10, TimeUnit.SECONDS))
            first.cancel()
            kotlinx.coroutines.delay(200)
            gate.countDown()
            assertTrue(withTimeout(10_000) { second.await() }.isFile)
        }
        assertFalse(request.cancelled(), "the download was not cancelled")
        assertEquals(listOf(photo.id), fetched.toList())
    }

    @Test
    fun `Closing during a download returns`() {
        gate = CountDownLatch(1)
        val photo = remote("p.jpg")
        runBlocking {
            async(start = CoroutineStart.UNDISPATCHED) { runCatching { cache.get(photo, RenderSize.THUMB) } }
            handedOver()
            val closing = Thread { cache.close() }.apply { start() }
            closing.join(TimeUnit.SECONDS.toMillis(5))
            assertFalse(closing.isAlive, "close() returned")
        }
    }
}
