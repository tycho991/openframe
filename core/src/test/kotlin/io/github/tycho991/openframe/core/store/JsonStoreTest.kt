@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.tycho991.openframe.core.store

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class JsonStoreTest {
    @Serializable
    data class Doc(
        val n: Int,
        val text: String = "",
    )

    @TempDir
    lateinit var dir: File

    private val log = AppLog(TestClock())

    @Test
    fun `Round trip`() =
        runBlocking {
            val scope = TestScope()
            val store = JsonStore(File(dir, "doc.json"), Doc.serializer(), scope, log)
            assertNull(store.load())
            store.saveNow(Doc(1, "one"))
            assertEquals(Doc(1, "one"), JsonStore(File(dir, "doc.json"), Doc.serializer(), scope, log).load())
            scope.cancel()
        }

    @Test
    fun `Interrupted write leaves the old state`() =
        runBlocking {
            val scope = TestScope()
            val file = File(dir, "doc.json")
            val store = JsonStore(file, Doc.serializer(), scope, log)
            store.saveNow(Doc(1))
            // Power loss in the middle of writing: the temp file is half written.
            File(dir, "doc.json.tmp").writeText("""{"n":2,"te""")
            assertEquals(Doc(1), JsonStore(file, Doc.serializer(), scope, log).load())
            assertFalse(File(dir, "doc.json.tmp").exists())
            scope.cancel()
        }

    private fun brokenCopies() =
        dir
            .list()
            .orEmpty()
            .filter { it.startsWith("doc.json.broken-") }
            .sorted()

    @Test
    fun `A read error renames nothing, and the next start reads the file`() =
        runBlocking {
            assumeFalse(System.getProperty("user.name") == "root", "root reads every file")
            val scope = TestScope()
            val file = File(dir, "doc.json")
            JsonStore(file, Doc.serializer(), scope, log).saveNow(Doc(5))
            file.setReadable(false)
            val store = JsonStore(file, Doc.serializer(), scope, log)
            assertNull(store.load())
            assertTrue(store.unreadable)
            file.setReadable(true)
            assertEquals(listOf("doc.json"), dir.list()!!.toList())
            assertEquals(Doc(5), JsonStore(file, Doc.serializer(), scope, log).load())
            scope.cancel()
        }

    @Test
    fun `A damaged file stays until the first write keeps it as a broken copy`() =
        runBlocking {
            val scope = TestScope()
            val file = File(dir, "doc.json").apply { writeText("{broken") }
            val store = JsonStore(file, Doc.serializer(), scope, log)
            assertNull(store.load())
            assertEquals("{broken", file.readText())
            assertEquals(emptyList<String>(), brokenCopies())

            store.saveNow(Doc(1))
            assertFalse(store.unreadable)
            assertEquals(Doc(1), JsonStore(file, Doc.serializer(), scope, log).load())
            assertEquals("{broken", File(dir, brokenCopies().single()).readText())
            store.saveNow(Doc(2))
            assertEquals(1, brokenCopies().size, "only the first write keeps a copy")
            scope.cancel()
        }

    @Test
    fun `Four incidents leave the three newest broken copies, and an old broken file stays`() =
        runBlocking {
            val scope = TestScope()
            val file = File(dir, "doc.json")
            val old = File(dir, "doc.json.broken").apply { writeText("of an earlier build") }
            for (n in 1..4) {
                file.writeText("{broken $n")
                val store = JsonStore(file, Doc.serializer(), scope, log)
                assertNull(store.load())
                store.saveNow(Doc(n))
            }
            assertEquals(JsonStore.KEPT_BROKEN, brokenCopies().size)
            assertEquals(setOf("{broken 2", "{broken 3", "{broken 4"), brokenCopies().map { File(dir, it).readText() }.toSet())
            assertEquals("of an earlier build", old.readText())
            scope.cancel()
        }

    @Test
    fun `A damaged file falls back to the copy of the last start`() =
        runBlocking {
            val scope = TestScope()
            val file = File(dir, "doc.json")
            JsonStore(file, Doc.serializer(), scope, log, backupOnLoad = true).saveNow(Doc(3, "kept"))
            assertEquals(Doc(3, "kept"), JsonStore(file, Doc.serializer(), scope, log, backupOnLoad = true).load())
            assertTrue(File(dir, "doc.json.bak").isFile)

            file.writeText("{broken")
            val store = JsonStore(file, Doc.serializer(), scope, log, backupOnLoad = true)
            assertEquals(Doc(3, "kept"), store.load())
            assertTrue(store.unreadable)
            assertEquals("{broken", file.readText())
            scope.cancel()
        }

    @Test
    fun `Without backupOnLoad there is no copy and no fallback`() =
        runBlocking {
            val scope = TestScope()
            val file = File(dir, "doc.json")
            JsonStore(file, Doc.serializer(), scope, log).saveNow(Doc(3))
            assertEquals(Doc(3), JsonStore(file, Doc.serializer(), scope, log).load())
            assertFalse(File(dir, "doc.json.bak").exists())
            File(dir, "doc.json.bak").writeText(StoreJson.encodeToString(Doc.serializer(), Doc(9)))
            file.writeText("{broken")
            assertNull(JsonStore(file, Doc.serializer(), scope, log).load())
            scope.cancel()
        }

    @Test
    fun `A failing copy does not make load throw`() =
        runBlocking {
            val scope = TestScope()
            val file = File(dir, "doc.json")
            JsonStore(file, Doc.serializer(), scope, log).saveNow(Doc(4))
            File(dir, "doc.json.bak.tmp").mkdir()
            assertEquals(Doc(4), JsonStore(file, Doc.serializer(), scope, log, backupOnLoad = true).load())
            scope.cancel()
        }

    @Test
    fun `A format error is logged with class and offset but quotes nothing of the file`() {
        val logged = AppLog(TestClock())
        val file = File(dir, "doc.json").apply { writeText("""{"n":1,"text":"Secret-Pw-42" oops}""") }
        assertNull(JsonStore(file, Doc.serializer(), TestScope(), logged).load())
        val entry = logged.recent(10).single { "doc.json" in it.message }
        assertTrue("offset" in entry.message, entry.message)
        assertFalse("Secret-Pw-42" in entry.message, entry.message)
        assertFalse("\tat " in entry.message, "no stack trace")
    }

    @Test
    fun `Unknown and missing fields`() {
        val file = File(dir, "doc.json").apply { writeText("""{"n":3,"removed":true}""") }
        assertEquals(Doc(3, ""), JsonStore(file, Doc.serializer(), TestScope(), log).load())
    }

    @Test
    fun `Batching writes only the latest state after the wait`() {
        val scope = TestScope(StandardTestDispatcher())
        val file = File(dir, "doc.json")
        val store = JsonStore(file, Doc.serializer(), scope, log, debounceMs = 1000)
        scope.runCurrent()
        repeat(50) { store.save(Doc(it)) }
        scope.advanceTimeBy(500)
        scope.runCurrent()
        assertFalse(file.exists())
        scope.advanceTimeBy(600)
        scope.runCurrent()
        assertEquals(Doc(49), store.load())
        scope.cancel()
    }

    @Test
    fun `Parallel writers`() =
        runBlocking(Dispatchers.Default) {
            val scope = TestScope()
            val file = File(dir, "doc.json")
            val store = JsonStore(file, Doc.serializer(), scope, log)
            (1..200).map { n -> async { store.saveNow(Doc(n, "x".repeat(n))) } }.awaitAll()
            val result = store.load()!!
            assertEquals("x".repeat(result.n), result.text)
            scope.cancel()
        }

    @Test
    fun `Failed flush keeps the state queued`() =
        runBlocking {
            val scope = TestScope()
            val file = File(dir, "doc.json")
            val store = JsonStore(file, Doc.serializer(), scope, log)
            // A directory in the place of the temp file makes writing fail.
            val blocker = File(dir, "doc.json.tmp").apply { mkdir() }
            store.save(Doc(7))
            assertThrows(IOException::class.java) { runBlocking { store.flush() } }
            assertFalse(file.exists())
            blocker.delete()
            store.flush()
            assertEquals(Doc(7), store.load())
            scope.cancel()
        }

    @Test
    fun `Writer survives a failed write and retries`() {
        val scope = TestScope(StandardTestDispatcher())
        val file = File(dir, "doc.json")
        val store = JsonStore(file, Doc.serializer(), scope, log, debounceMs = 1000)
        scope.runCurrent()
        val blocker = File(dir, "doc.json.tmp").apply { mkdir() }
        store.save(Doc(1))
        scope.advanceTimeBy(1100)
        scope.runCurrent()
        assertFalse(file.exists())
        blocker.delete()
        scope.advanceTimeBy(11_000)
        scope.runCurrent()
        assertTrue(file.exists())
        assertEquals(Doc(1), store.load())
        // A later save is written as well.
        store.save(Doc(2))
        scope.advanceTimeBy(1100)
        scope.runCurrent()
        assertEquals(Doc(2), store.load())
        scope.cancel()
    }

    @Test
    fun `A failed atomic write leaves no temporary file`() {
        val file = File(dir, "doc.json")
        // A directory with content in the place of the target: the rename fails after the temporary file is complete.
        File(file, "inside").apply { parentFile.mkdirs() }.writeText("x")
        assertThrows(IOException::class.java) { AtomicFile(file).writeText("{}") }
        assertFalse(File(dir, "doc.json.tmp").exists())
        assertThrows(IllegalStateException::class.java) { AtomicFile(File(dir, "other.json")).writeText("{}") { error("refused") } }
        assertFalse(File(dir, "other.json.tmp").exists())
    }
}
