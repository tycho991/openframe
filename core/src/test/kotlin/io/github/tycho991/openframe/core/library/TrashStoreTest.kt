package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.TestClock
import io.github.tycho991.openframe.core.testing.TestFrame
import io.github.tycho991.openframe.core.testing.waitFor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

/** Spec `photo-library`: Entries of the trash, Trash without its saved state, Retention, storage pressure. */
class TrashStoreTest {
    @TempDir
    lateinit var dir: File

    private val clock = TestClock(Instant.parse("2026-11-20T10:00:00Z"))
    private val log = AppLog(clock)
    private val photos by lazy { File(dir, "photos").apply { mkdirs() } }

    /** The free bytes every folder reports; `null` asks the real file system. */
    private var free: ((File) -> Long)? = null
    private var total: (File) -> Long = { 1_000_000_000L }

    private fun store() =
        TrashStore(
            photos,
            JsonStore(File(dir, "trash.json"), StoredTrash.serializer(), TestScope(), log, backupOnLoad = true),
            clock,
            log,
            freeBytes = { f -> free?.invoke(f) ?: f.usableSpace },
            totalBytes = { total(it) },
        ).also { it.load() }

    private fun entry(path: String): PhotoEntry {
        val file = File(photos, path)
        return PhotoEntry(PhotoEntry.idFor("local", path), "local", path, 800, 600, 1, file.length(), file.lastModified(), 3, hidden = true)
    }

    private fun photo(path: String): File = Fixtures.copy("landscape.jpg", File(photos, path))

    private fun delete(
        trash: TrashStore,
        path: String,
        by: String = "0123456789abcdef",
    ): TrashItem {
        photo(path)
        assertTrue(trash.moveIn(entry(path), File(photos, path), by))
        return trash.items().first { it.path == path }
    }

    /** An entry as a copy over USB would leave it: the folder and the file, no record. */
    private fun folder(
        id: String,
        vararg paths: String,
    ): File {
        val folder = File(photos, ".trash/$id").apply { mkdirs() }
        for (path in paths) Fixtures.copy("landscape.jpg", File(folder, path))
        return folder
    }

    @Test
    fun `Delete moves the file below its path into an entry with a record`() {
        val trash = store()
        val item = delete(trash, "holiday/beach.jpg")
        assertTrue(TrashStore.PATTERN.matches(item.id))
        assertEquals(clock.millis(), item.deletedAtMs)
        assertEquals(File(photos, ".trash/${item.id}/holiday/beach.jpg"), item.file)
        assertFalse(File(photos, "holiday/beach.jpg").exists())
        assertTrue(File(photos, ".trash/.nomedia").exists())
        assertEquals("0123456789abcdef", item.record?.deletedBy)
        assertEquals(PhotoEntry.idFor("local", "holiday/beach.jpg"), item.photoId)
        // After a restart the record is still there.
        runBlocking { trash.flush() }
        val again = store().items().single()
        assertEquals(item.id, again.id)
        assertEquals("0123456789abcdef", again.record?.deletedBy)
        assertTrue(again.record!!.entry.hidden)
    }

    /** › Trash without its saved state › State file lost. */
    @Test
    fun `A tree without trash json lists every entry without state`() {
        folder("1795400000000-0000000a", "holiday/beach.jpg")
        folder("1795400000001-0000000b", "b.jpg")
        val items = store().items()
        assertEquals(listOf("1795400000001-0000000b", "1795400000000-0000000a"), items.map { it.id }, "newest first")
        assertEquals(listOf("b.jpg", "holiday/beach.jpg"), items.map { it.path })
        assertTrue(items.all { it.record == null && !it.damaged && it.thumbEntry == null })
    }

    /** › Trash without its saved state › Deleted from the trash over USB. */
    @Test
    fun `A record without its folder is dropped and a removed file leaves the list at the next build`() {
        val trash = store()
        val gone = delete(trash, "a.jpg")
        val kept = delete(trash, "b.jpg")
        TrashStore.deleteTree(File(photos, ".trash/${gone.id}"))
        trash.purge()
        runBlocking { trash.flush() }
        assertEquals(listOf(kept.id), trash.items().map { it.id })
        assertNull(trash.get(gone.id))
        assertFalse(File(dir, "trash.json").readText().contains(gone.id), "its record went as well")
        assertTrue(File(dir, "trash.json").readText().contains(kept.id))
    }

    /** › Entries of the trash › A folder of the user's own; › Entry with two files. */
    @Test
    fun `Only pattern-named folders are entries, and one with two images is damaged`() {
        val own = File(photos, ".trash/.Trash/mine.jpg").also { Fixtures.copy("landscape.jpg", it) }
        val stray = File(photos, ".trash/notes.jpg").also { Fixtures.copy("landscape.jpg", it) }
        val oddName = File(photos, ".trash/1795400000000-XYZ/x.jpg").also { Fixtures.copy("landscape.jpg", it) }
        folder("1795400000000-0000000c", "a.jpg", "b.jpg")
        // An image only in a hidden folder would leave the scan's sight if restored.
        folder("1795400000000-0000000b", ".upload/x.jpg")
        folder("1795400000000-0000000d")
        File(photos, ".trash/1795400000000-0000000d/readme.txt").writeText("no photo")
        val trash = store()
        val items = trash.items().associateBy { it.id }
        assertEquals(setOf("1795400000000-0000000b", "1795400000000-0000000c", "1795400000000-0000000d"), items.keys)
        assertTrue(items.values.all { it.damaged && it.path == null && it.photoId == null })
        // Old entries all of them, by the clock: the purge removes the entries but nothing else.
        clock.advance(Duration.ofDays(400))
        trash.purge()
        assertTrue(trash.items().isEmpty())
        assertTrue(own.exists() && stray.exists() && oddName.exists(), "the user's own files stay")
    }

    @Test
    fun `A symbolic link inside an entry is neither followed when measuring nor when purging`() {
        val outside = File(dir, "outside").apply { mkdirs() }
        val precious = File(outside, "precious.jpg").also { Fixtures.copy("landscape.jpg", it) }
        val trash = store()
        val item = delete(trash, "a.jpg")
        val link = File(photos, ".trash/${item.id}/linked")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (e: Exception) {
            assumeTrue(false, "the file system of the test has no symbolic links")
        }
        trash.purge()
        val measured = trash.get(item.id)!!
        assertEquals(File(photos, ".trash/${item.id}/a.jpg").length(), measured.bytes, "the linked folder is not measured")
        assertFalse(measured.damaged, "the linked image is not counted")
        clock.advance(Duration.ofDays(8))
        trash.purge()
        assertNull(trash.get(item.id))
        assertFalse(File(photos, ".trash/${item.id}").exists(), "the entry went, the link with it")
        assertTrue(precious.exists(), "the folder outside keeps its files")
    }

    @Test
    fun `A linked trash folder or entry folder is no entry`() {
        val outside = File(dir, "outside").apply { mkdirs() }
        Fixtures.copy("landscape.jpg", File(outside, "x.jpg"))
        File(photos, ".trash").mkdirs()
        try {
            Files.createSymbolicLink(File(photos, ".trash/1795400000000-0000000e").toPath(), outside.toPath())
        } catch (e: Exception) {
            assumeTrue(false, "the file system of the test has no symbolic links")
        }
        val trash = store()
        assertTrue(trash.items().isEmpty())
        clock.advance(Duration.ofDays(400))
        trash.purge()
        assertTrue(File(outside, "x.jpg").exists())
    }

    /** › Retention of the trash › After 7 days. */
    @Test
    fun `An entry 7 days and an hour old goes, one 6 days old stays`() {
        val trash = store()
        val old = delete(trash, "old.jpg")
        clock.advance(Duration.ofDays(1).plusHours(1))
        val young = delete(trash, "young.jpg")
        clock.advance(Duration.ofDays(6))
        trash.purge()
        assertEquals(listOf(young.id), trash.items().map { it.id })
        assertFalse(File(photos, ".trash/${old.id}").exists())
        assertTrue(log.recent(100).any { it.message.startsWith("Trash purged: 1 entries") })
    }

    /** › Retention of the trash › Clock not yet set at the deletion. */
    @Test
    fun `An entry stamped by a clock not yet set is re-stamped once the clock passes the floor`() {
        clock.instant = Instant.parse("2019-06-01T08:00:00Z")
        val trash = store()
        val early = delete(trash, "a.jpg")
        // While the clock is below the floor nothing goes by age, however long it runs.
        clock.advance(Duration.ofDays(30))
        trash.purge()
        assertEquals(listOf(early.id), trash.items().map { it.id })
        // The clock is set: the entry gets the current time and a new ID, and stays 7 more days.
        clock.instant = Instant.parse("2026-11-20T10:00:00Z")
        trash.purge()
        val stamped = trash.items().single()
        assertTrue(stamped.id != early.id)
        assertEquals(clock.millis(), stamped.deletedAtMs)
        assertEquals("a.jpg", stamped.path)
        assertEquals(stamped.id, stamped.record?.trashId, "the record moved with it")
        assertTrue(File(photos, ".trash/${stamped.id}/a.jpg").isFile)
        assertNull(trash.get(early.id))
        clock.advance(Duration.ofDays(6))
        trash.purge()
        assertEquals(listOf(stamped.id), trash.items().map { it.id })
        clock.advance(Duration.ofDays(1).plusMinutes(1))
        trash.purge()
        assertTrue(trash.items().isEmpty())
    }

    @Test
    fun `An entry stamped more than an hour ahead is re-stamped`() {
        val ahead = clock.millis() + Duration.ofDays(3).toMillis()
        folder("$ahead-0000000f", "a.jpg")
        val trash = store()
        trash.purge()
        assertEquals(clock.millis(), trash.items().single().deletedAtMs)
    }

    /** › Trash under storage pressure › Upload makes room, › Not enough to gain, › Only recent entries. */
    @Test
    fun `Storage pressure frees only entries older than a day, oldest first and only as many as needed`() {
        val trash = store()
        val first = delete(trash, "1.jpg")
        clock.advance(Duration.ofMinutes(1))
        val second = delete(trash, "2.jpg")
        clock.advance(Duration.ofMinutes(1))
        val third = delete(trash, "3.jpg")
        val size = first.bytes
        // The free storage grows with every byte the trash gives back.
        val base = 1_000L
        free = { base + (3 * size - currentBytes()) }
        // All recent: nothing goes.
        assertFalse(trash.makeRoom(photos, base + size))
        assertEquals(3, trash.items().size)
        clock.advance(Duration.ofDays(2))
        // More than all of them could free: nothing goes.
        assertFalse(trash.makeRoom(photos, base + 3 * size + 1))
        assertEquals(3, trash.items().size)
        // One and a half entries: the two oldest go.
        assertTrue(trash.makeRoom(photos, base + size + size / 2))
        assertEquals(listOf(third.id), trash.items().map { it.id })
        assertFalse(File(photos, ".trash/${first.id}").exists() || File(photos, ".trash/${second.id}").exists())
    }

    private fun currentBytes(): Long =
        File(photos, ".trash")
            .walkTopDown()
            .filter { it.isFile && it.name != ".nomedia" }
            .sumOf { it.length() }

    @Test
    fun `Entries of the last day stay under pressure even when old ones go`() {
        val trash = store()
        val old = delete(trash, "old.jpg")
        clock.advance(Duration.ofDays(1).plusMinutes(1))
        val recent = delete(trash, "recent.jpg")
        free = { 0L + (2 * old.bytes - currentBytes()) }
        assertFalse(trash.makeRoom(photos, 2 * old.bytes), "the recent one alone would be needed as well")
        assertTrue(trash.makeRoom(photos, old.bytes))
        assertEquals(listOf(recent.id), trash.items().map { it.id })
    }

    /** › Where storage pressure frees the trash: only the trash's own volume. */
    @Test
    fun `A folder on another volume frees nothing`() {
        val trash = store()
        delete(trash, "a.jpg")
        clock.advance(Duration.ofDays(2))
        val cache = File(dir, "cache").apply { mkdirs() }
        free = { 0L }
        total = { if (it == cache) 2_000_000_000L else 1_000_000_000L }
        assertFalse(trash.makeRoom(cache, 1))
        assertEquals(1, trash.items().size)
        assertEquals(0, trash.reclaimableBytes(cache))
    }

    @Test
    fun `While the clock is below the floor nothing goes under pressure either`() {
        clock.instant = Instant.parse("2019-06-01T08:00:00Z")
        val trash = store()
        delete(trash, "a.jpg")
        clock.advance(Duration.ofDays(30))
        free = { 0L }
        assertFalse(trash.makeRoom(photos, 1))
        assertEquals(1, trash.items().size)
    }

    @Test
    fun `A failed move leaves no record and no folder`() {
        val trash = store()
        photo("a.jpg")
        trash.renameFile = { _, _ -> false }
        assertFalse(trash.moveIn(entry("a.jpg"), File(photos, "a.jpg"), "0123456789abcdef"))
        assertTrue(File(photos, "a.jpg").exists())
        assertTrue(trash.items().isEmpty())
        assertTrue(File(photos, ".trash").listFiles().orEmpty().none { TrashStore.PATTERN.matches(it.name) })
        runBlocking { trash.flush() }
        assertFalse(File(dir, "trash.json").exists() && File(dir, "trash.json").readText().contains("a.jpg"))
    }

    /** The first build runs after the server has started, not before it (design decision 3). */
    @Test
    fun `The first build runs after the server started`() {
        folder("1795400000000-00000010", "a.jpg")
        File(dir, "frame/photos").mkdirs()
        File(photos, ".trash").renameTo(File(dir, "frame/photos/.trash"))
        TestFrame(File(dir, "frame")).use { frame ->
            waitFor(message = "the trash built") {
                frame.core.log
                    .recent(500)
                    .any { it.message.startsWith("Trash: 1 entries") }
            }
            val messages =
                frame.core.log
                    .recent(500)
                    .map { it.message }
            val server = messages.indexOfFirst { it.startsWith("Server on ") }
            val built = messages.indexOfFirst { it.startsWith("Trash: 1 entries") }
            assertTrue(server in 0 until built, messages.toString())
        }
    }
}
