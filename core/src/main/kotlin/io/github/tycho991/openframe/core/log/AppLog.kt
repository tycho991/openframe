package io.github.tycho991.openframe.core.log

import io.github.tycho991.openframe.core.platform.Clock
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val time: Instant,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

/** Empfänger für Protokolleinträge, z. B. Logcat in :android. */
fun interface LogSink {
    fun write(entry: LogEntry)
}

/**
 * Protokoll der App. Hält die letzten Einträge im Speicher und schreibt sie
 * optional in ein rotierendes Datei-Log mit begrenzter Größe.
 */
class AppLog(
    private val clock: Clock,
    private val file: RotatingFileLog? = null,
    private val memoryCapacity: Int = 1000,
    private val sinks: List<LogSink> = emptyList(),
) {
    private val buffer = ArrayDeque<LogEntry>()

    fun d(
        tag: String,
        message: String,
    ) = log(LogLevel.DEBUG, tag, message)

    fun i(
        tag: String,
        message: String,
    ) = log(LogLevel.INFO, tag, message)

    fun w(
        tag: String,
        message: String,
        error: Throwable? = null,
    ) = log(LogLevel.WARN, tag, message, error)

    fun e(
        tag: String,
        message: String,
        error: Throwable? = null,
    ) = log(LogLevel.ERROR, tag, message, error)

    fun log(
        level: LogLevel,
        tag: String,
        message: String,
        error: Throwable? = null,
    ) {
        val text = if (error == null) message else "$message\n${stackTrace(error)}"
        val entry = LogEntry(clock.now(), level, tag, text)
        synchronized(buffer) {
            buffer.addLast(entry)
            while (buffer.size > memoryCapacity) buffer.removeFirst()
        }
        file?.append(entry)
        sinks.forEach { it.write(entry) }
    }

    /** Die letzten [limit] Einträge, älteste zuerst. Liest das Datei-Log, wenn vorhanden. */
    fun recent(limit: Int): List<LogEntry> = file?.read(limit) ?: synchronized(buffer) { buffer.toList().takeLast(limit) }

    private fun stackTrace(error: Throwable): String = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
}

/**
 * Datei-Log mit zwei Dateien: Erreicht die aktuelle Datei [maxBytes], wird sie
 * zur alten Datei und eine neue beginnt. Belegt so höchstens 2 × [maxBytes].
 *
 * Format pro Zeile: `ISO-Zeit<TAB>LEVEL<TAB>tag<TAB>Nachricht` mit maskierten Zeilenumbrüchen.
 */
class RotatingFileLog(
    private val dir: File,
    private val maxBytes: Long = 512 * 1024,
) {
    private val current = File(dir, "openframe.log")
    private val previous = File(dir, "openframe.log.1")

    @Synchronized
    fun append(entry: LogEntry) {
        dir.mkdirs()
        if (current.length() >= maxBytes) {
            previous.delete()
            current.renameTo(previous)
        }
        current.appendText(format(entry) + "\n")
    }

    @Synchronized
    fun read(limit: Int): List<LogEntry> {
        val lines = sequenceOf(previous, current).filter { it.exists() }.flatMap { it.readLines() }.toList()
        return lines.takeLast(limit).mapNotNull(::parse)
    }

    private fun format(entry: LogEntry): String =
        listOf(entry.time.toString(), entry.level.name, escape(entry.tag), escape(entry.message)).joinToString("\t")

    private fun parse(line: String): LogEntry? {
        val parts = line.split('\t', limit = 4)
        if (parts.size != 4) return null
        return runCatching {
            LogEntry(Instant.parse(parts[0]), LogLevel.valueOf(parts[1]), unescape(parts[2]), unescape(parts[3]))
        }.getOrNull()
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")

    private fun unescape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    else -> out.append(s[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
