package io.github.tycho991.openframe.core.log

import io.github.tycho991.openframe.core.platform.Clock
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.time.Instant

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val time: Instant,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

/** Receiver for log entries, e.g. Logcat in :android. */
fun interface LogSink {
    fun write(entry: LogEntry)
}

/**
 * The app's log. Keeps the latest entries in memory and optionally writes them to a rotating file log of limited
 * size (spec `kiosk-host`, "Diagnostics").
 *
 * Logging never throws: the file log and every sink are guarded. After a file error the file log pauses for
 * [FILE_PAUSE_MS]; the entries of that time are kept only in memory, and [recent] returns them together with the
 * file. Stack traces are cut to [MAX_FRAMES] frames per cause and entries to [MAX_ENTRY_CHARS] characters. A warning
 * or error repeated with the same level, tag, message and exception class within [REPEAT_WINDOW_MS] is written once
 * with its stack trace and afterwards at most every [REPEAT_WINDOW_MS] as one line with a counter; [recovered] ends it.
 */
class AppLog(
    private val clock: Clock,
    private val file: RotatingFileLog? = null,
    private val memoryCapacity: Int = if (file != null) 100 else 1000,
    private val sinks: List<LogSink> = emptyList(),
) {
    private val lock = Any()
    private val buffer = ArrayDeque<Buffered>()
    private val repeats = LinkedHashMap<RepeatKey, Repeat>()
    private var filePausedUntil = Long.MIN_VALUE

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
        try {
            synchronized(lock) {
                val now = clock.monotonicMillis()
                if (level < LogLevel.WARN || !collapse(RepeatKey(level, tag, message, error?.javaClass?.name), now)) {
                    write(level, tag, if (error == null) message else "$message\n${stackTrace(error)}")
                }
                reportDue(now)
            }
        } catch (e: Throwable) {
            // Not even the clock or the memory buffer worked; the log must not end its caller.
            if (e is VirtualMachineError && e !is OutOfMemoryError && e !is StackOverflowError) throw e
        }
    }

    /**
     * Ends the repetition of the failures of [tag] whose message starts with [failurePrefix]: if one occurred within
     * the last [REPEAT_WINDOW_MS], writes [message] with the number of failures, so that the next failure is logged
     * again with its stack trace.
     */
    fun recovered(
        tag: String,
        failurePrefix: String,
        message: String,
    ) {
        try {
            synchronized(lock) {
                reportDue(clock.monotonicMillis())
                val ended = repeats.entries.filter { it.key.tag == tag && it.key.message.startsWith(failurePrefix) }
                if (ended.isEmpty()) return
                ended.forEach { repeats.remove(it.key) }
                val total = ended.sumOf { it.value.total }
                val since = ended.minOf { it.value.since }
                write(LogLevel.INFO, tag, "$message (failed $total ${if (total == 1) "time" else "times"} since $since)")
            }
        } catch (e: Throwable) {
            if (e is VirtualMachineError && e !is OutOfMemoryError && e !is StackOverflowError) throw e
        }
    }

    /**
     * The latest [limit] entries, oldest first: the file log if it is readable, with the entries kept only in memory
     * while it was paused; otherwise the memory buffer.
     */
    fun recent(limit: Int): List<LogEntry> {
        val (memory, onlyInMemory) =
            synchronized(lock) {
                reportDue(clock.monotonicMillis())
                buffer.map { it.entry } to buffer.filter { !it.inFile }.map { it.entry }
            }
        val fromFile =
            file?.let {
                try {
                    it.read(limit)
                } catch (e: Exception) {
                    null
                }
            } ?: return memory.takeLast(limit)
        return (fromFile + onlyInMemory).sortedBy { it.time }.takeLast(limit)
    }

    /** Whether the entry is a repetition to leave out; counts it and writes the counter line when it is due. */
    private fun collapse(
        key: RepeatKey,
        now: Long,
    ): Boolean {
        val repeat = repeats[key]
        if (repeat == null || now - repeat.lastSeen >= REPEAT_WINDOW_MS) {
            if (repeat != null && repeat.unreported > 0) report(key, repeat, now)
            repeats[key] = Repeat(clock.now(), now)
            return false
        }
        repeat.total++
        repeat.unreported++
        repeat.lastSeen = now
        if (now - repeat.lastLine >= REPEAT_WINDOW_MS) report(key, repeat, now)
        return true
    }

    /** Writes the counter lines that are due and forgets the failures that did not repeat within the window. */
    private fun reportDue(now: Long) {
        if (repeats.isEmpty()) return
        val iterator = repeats.entries.iterator()
        while (iterator.hasNext()) {
            val (key, repeat) = iterator.next()
            if (repeat.unreported > 0 && now - repeat.lastLine >= REPEAT_WINDOW_MS) report(key, repeat, now)
            if (now - repeat.lastSeen >= REPEAT_WINDOW_MS) iterator.remove()
        }
    }

    private fun report(
        key: RepeatKey,
        repeat: Repeat,
        now: Long,
    ) {
        write(key.level, key.tag, "${key.message} (still failing: ${repeat.total} times since ${repeat.since})")
        repeat.unreported = 0
        repeat.lastLine = now
    }

    private fun write(
        level: LogLevel,
        tag: String,
        text: String,
    ) {
        val capped = if (text.length > MAX_ENTRY_CHARS) text.take(MAX_ENTRY_CHARS) + "\n… (cut)" else text
        val entry = LogEntry(clock.now(), level, tag, capped)
        val inFile = appendToFile(entry)
        buffer.addLast(Buffered(entry, inFile))
        while (buffer.size > memoryCapacity) buffer.removeFirst()
        sinks.forEach {
            try {
                it.write(entry)
            } catch (e: Exception) {
                // A sink such as Logcat must not end the caller; the entry is in the buffer and possibly in the file.
            }
        }
    }

    private fun appendToFile(entry: LogEntry): Boolean {
        val file = file ?: return false
        val now = clock.monotonicMillis()
        if (now < filePausedUntil) return false
        return try {
            file.append(entry)
            true
        } catch (e: Exception) {
            filePausedUntil = now + FILE_PAUSE_MS
            val notice = LogEntry(entry.time, LogLevel.WARN, TAG, "File log paused for ${FILE_PAUSE_MS / 1000} s: $e")
            buffer.addLast(Buffered(notice, false))
            false
        }
    }

    private class Buffered(
        val entry: LogEntry,
        val inFile: Boolean,
    )

    private data class RepeatKey(
        val level: LogLevel,
        val tag: String,
        val message: String,
        val errorClass: String?,
    )

    private class Repeat(
        val since: Instant,
        var lastLine: Long,
    ) {
        var lastSeen = lastLine
        var total = 1
        var unreported = 0
    }

    companion object {
        const val FILE_PAUSE_MS = 60_000L
        const val REPEAT_WINDOW_MS = 10 * 60_000L
        const val MAX_FRAMES = 30
        const val MAX_ENTRY_CHARS = 8 * 1024
        private const val TAG = "log"

        /** Like [Throwable.printStackTrace], with at most [MAX_FRAMES] frames per cause. */
        fun stackTrace(error: Throwable): String {
            val out = StringBuilder()
            val seen = HashSet<Throwable>()
            var current: Throwable? = error
            var prefix = ""
            while (current != null && seen.add(current)) {
                out.append(prefix).append(current.toString())
                val frames = current.stackTrace
                frames.take(MAX_FRAMES).forEach { out.append("\n\tat ").append(it) }
                if (frames.size > MAX_FRAMES) out.append("\n\t... ").append(frames.size - MAX_FRAMES).append(" more")
                current = current.cause
                prefix = "\nCaused by: "
            }
            return out.toString()
        }
    }
}

/**
 * File log with two files: when the current file reaches [maxBytes], it becomes
 * the old file and a new one begins. Occupies at most 2 × [maxBytes] that way.
 *
 * Format per line: `ISO time<TAB>LEVEL<TAB>tag<TAB>message`; backslash, line break, tab and carriage return are
 * escaped as `\\`, `\n`, `\t` and `\r`, every other character below 0x20 as `\u00XX`.
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
        val before = current.length()
        try {
            current.appendText(format(entry) + "\n")
        } catch (e: IOException) {
            // A full disk may take part of the line: cut it off again, which needs no space, so that the next line
            // does not join it and the entry is not read twice (from the file and from the memory buffer).
            try {
                RandomAccessFile(current, "rw").use { it.setLength(before) }
            } catch (ignored: IOException) {
            }
            throw e
        }
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

    private fun escape(s: String): String {
        if (s.none { it == '\\' || it < ' ' }) return s
        val out = StringBuilder(s.length + 8)
        for (c in s) {
            when {
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\t' -> out.append("\\t")
                c == '\r' -> out.append("\\r")
                c < ' ' -> out.append("\\u").append("%04x".format(c.code))
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private fun unescape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val code = if (s[i + 1] == 'u' && i + 6 <= s.length) s.substring(i + 2, i + 6).toIntOrNull(16) else null
                if (code != null) {
                    out.append(code.toChar())
                    i += 6
                    continue
                }
                when (s[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
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
