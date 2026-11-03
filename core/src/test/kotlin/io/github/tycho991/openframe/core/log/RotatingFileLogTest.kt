package io.github.tycho991.openframe.core.log

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

/** Spec `kiosk-host`: Diagnostics. */
class RotatingFileLogTest {
    @TempDir
    lateinit var dir: File

    private fun entry(message: String) = LogEntry(Instant.parse("2026-05-01T10:15:30Z"), LogLevel.INFO, "test", message)

    @Test
    fun `the file rotates at the limit and keeps at most two files`() {
        val log = RotatingFileLog(dir, maxBytes = 200)
        repeat(20) { log.append(entry("entry number $it")) }

        assertEquals(setOf("openframe.log", "openframe.log.1"), dir.list()!!.toSet())
        assertTrue(File(dir, "openframe.log.1").length() >= 200)
        val messages = log.read(100).map { it.message }
        assertEquals("entry number 19", messages.last())
        assertFalse("entry number 0" in messages)
        assertEquals(messages.sortedBy { it.substringAfterLast(' ').toInt() }, messages)
    }

    @Test
    fun `control characters round-trip and every entry stays one line`() {
        val log = RotatingFileLog(dir)
        val messages =
            listOf(
                "back\\slash \\n literally",
                "line\nbreak",
                "tab\there",
                "Tablet\rX",
                "bell\u0007 escape\u001b nul\u0000 end",
                "\\u0041 stays text",
            )
        messages.forEach { log.append(entry(it)) }

        assertEquals(messages, log.read(100).map { it.message })
        val lines = File(dir, "openframe.log").readText().split('\n').filter { it.isNotEmpty() }
        assertEquals(messages.size, lines.size)
        assertTrue(lines.none { line -> line.any { it < ' ' && it != '\t' } })
    }

    @Test
    fun `lines of earlier builds are still read`() {
        File(dir, "openframe.log").writeText("2026-05-01T10:15:30Z\tINFO\ttest\tone\\ntwo \\\\ three\\tfour\n")
        assertEquals("one\ntwo \\ three\tfour", RotatingFileLog(dir).read(10).single().message)
    }
}
