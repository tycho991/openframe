package io.github.tycho991.openframe.core.sources

import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.PhotoIndexStore
import io.github.tycho991.openframe.core.library.StoredIndex
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Design decision 10 of `nextcloud-hardening`: the originals store keeps a running total, so a budget check costs nothing. */
class OriginalsStoreTest {
    @TempDir
    lateinit var dir: File

    private val listings = AtomicInteger()
    private val stats = HashMap<String, Int>()

    /** A file that counts how often it is asked for its time and size. */
    private inner class Counted(
        file: File,
    ) : File(file.path) {
        override fun lastModified(): Long {
            synchronized(stats) { stats.merge("$name.lastModified", 1, Int::plus) }
            return super.lastModified()
        }

        override fun length(): Long {
            synchronized(stats) { stats.merge("$name.length", 1, Int::plus) }
            return super.length()
        }
    }

    private fun store(folder: File) =
        OriginalsStore(folder, listFolder = {
            listings.incrementAndGet()
            it.listFiles()?.map { f -> Counted(f) }?.toTypedArray()
        })

    private fun entry(n: Int) = PhotoEntry("nextcloud-$n", "nextcloud", "p$n.jpg", 1, 1, 0, 0, 0, 1)

    @Test
    fun `A sync lists the folder once, not once per budget check`() {
        val log = AppLog(TestClock())
        val remote = FakeRemoteFolder()
        repeat(30) { remote.put("p$it.jpg", Fixtures.file("landscape.jpg").readBytes(), "e$it") }
        val configs =
            SourceConfigStore(JsonStore(File(dir, "sources.json"), StoredSources.serializer(), TestScope(), log)).also {
                it.load()
                it.add(FakeKind.source("nextcloud"))
            }
        val index = PhotoIndexStore(JsonStore(File(dir, "index.json"), StoredIndex.serializer(), TestScope(), log)).also { it.load() }
        val originals = store(File(dir, "originals"))
        val source =
            RemoteSource("nextcloud", { configs.get("nextcloud") }, FakeKind { remote }, index, JvmMediaEngine(), originals, log)
        runBlocking { source.scan() }
        val first = listings.get()
        assertEquals(30, originals.totalBytes() / Fixtures.file("landscape.jpg").length())
        // Every original was removed from the cache: 30 budget checks in the next sync.
        originals.clear()
        listings.set(0)
        runBlocking { source.scan() }
        assertEquals(1, listings.get(), "one listing at the start of the sync (the first sync: $first)")
        assertEquals(30 * Fixtures.file("landscape.jpg").length(), originals.totalBytes())
    }

    @Test
    fun `A trim asks each file for its time and size once`() {
        val folder = File(dir, "trim").apply { mkdirs() }
        val store = store(folder)
        for (n in 1..5) {
            store.fileOf(entry(n)).writeBytes(ByteArray(1000))
            store.fileOf(entry(n)).setLastModified(10_000L + n * 1000L)
        }
        store.refresh()
        stats.clear()
        assertEquals(3, store.trim(2_000))
        for (n in 1..5) {
            assertTrue((stats["nextcloud-$n.jpg.lastModified"] ?: 0) <= 1, "$stats")
            assertTrue((stats["nextcloud-$n.jpg.length"] ?: 0) <= 1, "$stats")
        }
        // Within the budget, a trim does not even list the folder.
        listings.set(0)
        assertEquals(0, store.trim(2_000))
        assertEquals(0, listings.get())
    }

    @Test
    fun `A pinned original survives a trim below the budget`() {
        val folder = File(dir, "pin").apply { mkdirs() }
        val store = OriginalsStore(folder)
        for (n in 1..3) {
            store.fileOf(entry(n)).writeBytes(ByteArray(1000))
            store.fileOf(entry(n)).setLastModified(10_000L + n * 1000L)
        }
        store.pin(entry(1))
        assertEquals(2, store.trim(0))
        assertTrue(store.has(entry(1)), "the oldest, but pinned")
        assertFalse(store.has(entry(2)) || store.has(entry(3)))
        store.unpin(entry(1))
        assertEquals(1, store.trim(0))
        assertFalse(store.has(entry(1)))
    }

    @Test
    fun `The total follows commits, replacements and removals`() {
        val store = OriginalsStore(File(dir, "total"))
        assertEquals(0, store.totalBytes())
        store.partOf(entry(1)).writeBytes(ByteArray(100))
        store.commit(entry(1))
        store.partOf(entry(1)).writeBytes(ByteArray(250))
        store.commit(entry(1))
        store.partOf(entry(2)).writeBytes(ByteArray(50))
        store.commit(entry(2))
        assertEquals(300, store.totalBytes())
        assertTrue(store.fitsBudget(700, 1000) && !store.fitsBudget(701, 1000))
        store.remove(entry(1).id)
        assertEquals(50, store.totalBytes())
        store.clear()
        assertEquals(0, store.totalBytes())
    }

    @Test
    fun `Removing many originals at once lists the folder once and keeps the others and the total right`() {
        val store = store(File(dir, "many"))
        // `nextcloud-1` is a prefix of `nextcloud-10`: only the originals of the removed IDs may go.
        for (n in 1..20) {
            store.partOf(entry(n)).writeBytes(ByteArray(10))
            store.commit(entry(n))
        }
        store.partOf(entry(21)).writeBytes(ByteArray(10))
        assertEquals(200, store.totalBytes())
        val before = listings.get()

        store.removeAll((1..9).map { entry(it).id } + entry(21).id)

        assertEquals(before + 1, listings.get())
        assertEquals((10..20).map { store.fileOf(entry(it)).name }.toSet(), File(dir, "many").list()!!.toSet())
        assertEquals(110, store.totalBytes())
    }
}
