package io.github.tycho991.openframe.core.log

import io.github.tycho991.openframe.core.testing.TestClock
import io.github.tycho991.openframe.core.testing.TestFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.Duration

/** Spec `kiosk-host`: Diagnostics. */
class AppLogTest {
    @TempDir
    lateinit var dir: File

    private val clock = TestClock()

    @Test
    fun `a log folder that is a file does not stop the start, and the API returns the entries kept in memory`() {
        File(dir, "state").mkdirs()
        File(dir, "state/logs").writeText("not a folder")
        TestFrame(dir).use { frame ->
            frame.core.log.i("test", "kept in memory")
            val res = frame.get("/api/v1/logs?limit=1000")
            assertEquals(200, res.statusCode())
            assertTrue("kept in memory" in res.body(), res.body())
        }
    }

    @Test
    fun `a throwing sink and a failing file end neither the caller nor the other sinks`() {
        val seen = mutableListOf<String>()
        val blocked = File(dir, "logs").apply { writeText("file") }
        val log =
            AppLog(
                clock,
                RotatingFileLog(blocked),
                sinks = listOf(LogSink { throw IllegalStateException("sink") }, LogSink { seen += it.message }),
            )
        log.e("test", "first", IOException("disk"))
        log.i("test", "second")
        assertEquals(listOf("first", "second"), seen.map { it.lineSequence().first() })
        assertEquals(listOf("first", "second"), log.recent(10).filter { it.tag == "test" }.map { it.message.lineSequence().first() })
    }

    @Test
    fun `the file log pauses after an error and recent adds the entries kept only in memory`() {
        val logs = File(dir, "logs")
        val log = AppLog(clock, RotatingFileLog(logs))
        log.i("test", "one")
        logs.deleteRecursively()
        logs.writeText("blocks the folder")
        log.i("test", "two")
        logs.delete()
        clock.advance(Duration.ofSeconds(30))
        log.i("test", "three")
        clock.advance(Duration.ofSeconds(31))
        log.i("test", "four")

        val messages = log.recent(100).filter { it.tag == "test" }.map { it.message }
        assertEquals(listOf("two", "three", "four"), messages)
        assertEquals(listOf("four"), RotatingFileLog(logs).read(100).map { it.message })
        assertTrue(log.recent(100).any { it.tag == "log" && "paused" in it.message })
    }

    @Test
    fun `the memory buffer keeps 100 entries with a file log and 1000 without`() {
        val withFile = AppLog(clock, RotatingFileLog(File(dir, "a").apply { writeText("file") }))
        val withoutFile = AppLog(clock)
        repeat(1500) {
            withFile.i("test", "entry $it")
            withoutFile.i("test", "entry $it")
        }
        assertEquals(100, withFile.recent(5000).size)
        assertEquals(1000, withoutFile.recent(5000).size)
    }

    @Test
    fun `100 identical errors give one stack trace and one counter line`() {
        val log = AppLog(clock)
        repeat(100) {
            log.e("store", "index.json could not be written", IOException("No space left on device"))
            clock.advance(Duration.ofSeconds(1))
        }
        clock.advance(Duration.ofMinutes(10))

        val entries = log.recent(1000)
        assertEquals(2, entries.size, entries.joinToString("\n") { it.message })
        assertTrue("java.io.IOException: No space left on device" in entries[0].message)
        assertTrue("still failing: 100 times" in entries[1].message, entries[1].message)
        assertTrue("\tat " !in entries[1].message)
    }

    @Test
    fun `repetitions are counted every 10 minutes and a recovery ends them`() {
        val log = AppLog(clock)
        repeat(130) {
            log.e("store", "index.json could not be written", IOException("disk"))
            clock.advance(Duration.ofSeconds(10))
        }
        log.recovered("store", "index.json could not be written", "index.json written again")
        log.e("store", "index.json could not be written", IOException("disk"))

        val messages = log.recent(1000).map { it.message.lineSequence().first() }
        assertEquals(
            listOf(
                "index.json could not be written",
                "index.json could not be written (still failing: 61 times since 2026-05-01T10:15:30Z)",
                "index.json could not be written (still failing: 121 times since 2026-05-01T10:15:30Z)",
                "index.json written again (failed 130 times since 2026-05-01T10:15:30Z)",
                "index.json could not be written",
            ),
            messages,
        )
        assertEquals(2, log.recent(1000).count { "\tat " in it.message })
    }

    @Test
    fun `different messages, levels and exceptions are not collapsed`() {
        val log = AppLog(clock)
        log.e("store", "a", IOException())
        log.e("store", "a", IllegalStateException())
        log.w("store", "a", IOException())
        log.e("store", "b", IOException())
        log.i("store", "info")
        log.i("store", "info")
        assertEquals(6, log.recent(100).size)
    }

    @Test
    fun `a recovery without an earlier failure writes nothing`() {
        val log = AppLog(clock)
        log.recovered("store", "index.json could not be written", "index.json written again")
        assertEquals(emptyList<LogEntry>(), log.recent(100))
    }

    @Test
    fun `stack traces keep 30 frames per cause and entries 8 KiB`() {
        fun deep(n: Int): Nothing = if (n == 0) throw IOException("deep") else deep(n - 1)
        val error =
            try {
                deep(100)
            } catch (e: IOException) {
                IllegalStateException("outer", e)
            }
        val log = AppLog(clock)
        log.e("test", "failure", error)
        val text = log.recent(1).single().message
        val causes = text.split("\nCaused by: ")
        assertEquals(2, causes.size)
        causes.forEach { assertEquals(AppLog.MAX_FRAMES, it.lines().count { line -> line.startsWith("\tat ") }) }
        assertTrue(causes[1].startsWith("java.io.IOException: deep"))
        assertTrue(text.contains(" more"))

        log.i("test", "x".repeat(20_000))
        assertTrue(
            log
                .recent(1)
                .single()
                .message.length <= AppLog.MAX_ENTRY_CHARS + 20,
        )
    }
}
