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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

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
    fun `Rundlauf`() =
        runBlocking {
            val scope = TestScope()
            val store = JsonStore(File(dir, "doc.json"), Doc.serializer(), scope, log)
            assertNull(store.load())
            store.saveNow(Doc(1, "eins"))
            assertEquals(Doc(1, "eins"), JsonStore(File(dir, "doc.json"), Doc.serializer(), scope, log).load())
            scope.cancel()
        }

    @Test
    fun `Unterbrochenes Schreiben hinterlaesst den alten Stand`() =
        runBlocking {
            val scope = TestScope()
            val file = File(dir, "doc.json")
            val store = JsonStore(file, Doc.serializer(), scope, log)
            store.saveNow(Doc(1))
            // Stromausfall mitten im Schreiben: die Temp-Datei ist halb geschrieben.
            File(dir, "doc.json.tmp").writeText("""{"n":2,"te""")
            assertEquals(Doc(1), JsonStore(file, Doc.serializer(), scope, log).load())
            assertFalse(File(dir, "doc.json.tmp").exists())
            scope.cancel()
        }

    @Test
    fun `Beschaedigte Datei wird beiseitegelegt`() {
        val file = File(dir, "doc.json").apply { writeText("{kaputt") }
        assertNull(JsonStore(file, Doc.serializer(), TestScope(), log).load())
        assertFalse(file.exists())
        assertEquals("{kaputt", File(dir, "doc.json.broken").readText())
    }

    @Test
    fun `Unbekannte und fehlende Felder`() {
        val file = File(dir, "doc.json").apply { writeText("""{"n":3,"entfernt":true}""") }
        assertEquals(Doc(3, ""), JsonStore(file, Doc.serializer(), TestScope(), log).load())
    }

    @Test
    fun `Buendelung schreibt nur den letzten Stand nach der Wartezeit`() {
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
    fun `Parallele Schreiber`() =
        runBlocking(Dispatchers.Default) {
            val scope = TestScope()
            val file = File(dir, "doc.json")
            val store = JsonStore(file, Doc.serializer(), scope, log)
            (1..200).map { n -> async { store.saveNow(Doc(n, "x".repeat(n))) } }.awaitAll()
            val result = store.load()!!
            assertEquals("x".repeat(result.n), result.text)
            scope.cancel()
        }
}
