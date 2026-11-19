package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.causes
import io.github.tycho991.openframe.core.platform.Clock
import io.github.tycho991.openframe.core.sources.SourceLimits
import java.io.IOException
import java.io.InputStream

/**
 * Wraps every body read from a source server (design decision 1 of `nextcloud-hardening`): at most [maxBytes], within
 * [totalMs] from the first read, at least [minBytesPerWindow] per [windowMs], and nothing more once [cancelled] says so.
 * A separate [roomBytes] stands for the free storage a download may take. The time runs on [Clock.monotonicMillis], so
 * a jump of the wall clock never makes a body "too slow".
 *
 * Its failures are [IOException]s of its own, so that the SAX parsers of the JVM and of Android pass them on alike;
 * the callers translate them into kinds of failure.
 */
class LimitedResponseStream(
    private val input: InputStream,
    private val clock: Clock,
    /** What the body is, for the messages: "The listing of /Photos". */
    private val what: String,
    private val maxBytes: Long,
    private val totalMs: Long,
    private val roomBytes: Long = Long.MAX_VALUE,
    private val cancelled: () -> Boolean = { false },
    private val minBytesPerWindow: Long = SourceLimits.MIN_BYTES_PER_WINDOW,
    private val windowMs: Long = SourceLimits.WINDOW_MS,
) : InputStream() {
    /** The body is larger than its limit. */
    class LimitExceeded(
        message: String,
    ) : IOException(message)

    /** The body would leave less free storage than the reserve. */
    class NoRoom(
        message: String,
    ) : IOException(message)

    /** The body comes too slowly, or takes longer than its total time. */
    class TooSlow(
        message: String,
    ) : IOException(message)

    /** The source was removed while its body was read. */
    class Cancelled(
        message: String,
    ) : IOException(message)

    private var total = 0L
    private var startMs = -1L
    private var windowStartMs = 0L
    private var windowBytes = 0L

    /** The bytes read so far. */
    val bytesRead: Long get() = total

    override fun read(): Int {
        val one = ByteArray(1)
        while (true) {
            val n = read(one, 0, 1)
            if (n < 0) return -1
            if (n == 1) return one[0].toInt() and 0xff
        }
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (len == 0) return 0
        check()
        // One byte more than allowed is enough to tell that the body is too large.
        val allowed = minOf(maxBytes, roomBytes)
        val ask = minOf(len.toLong(), allowed - total + 1).coerceAtLeast(1L).toInt()
        val n = input.read(b, off, ask)
        if (n < 0) return -1
        total += n
        windowBytes += n
        if (total > roomBytes) throw NoRoom("Not enough free storage on the frame for $what")
        if (total > maxBytes) throw LimitExceeded("$what is larger than ${describe(maxBytes)}")
        checkTime(clock.monotonicMillis())
        return n
    }

    override fun skip(n: Long): Long {
        // Through read, so that skipped bytes count as well.
        val buffer = ByteArray(minOf(n, 8192L).toInt().coerceAtLeast(1))
        var left = n
        while (left > 0) {
            val r = read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (r < 0) break
            left -= r
        }
        return n - left
    }

    override fun available(): Int = input.available()

    override fun close() = input.close()

    private fun check() {
        if (cancelled()) throw Cancelled("$what was ended: the source was removed")
        val now = clock.monotonicMillis()
        if (startMs < 0) {
            startMs = now
            windowStartMs = now
        }
        checkTime(now)
    }

    private fun checkTime(now: Long) {
        if (now - startMs > totalMs) throw TooSlow("$what took longer than ${totalMs / 60_000} minutes")
        if (now - windowStartMs >= windowMs) {
            if (windowBytes < minBytesPerWindow) {
                throw TooSlow("$what came too slowly (less than ${describe(minBytesPerWindow)} in ${windowMs / 1000} seconds)")
            }
            windowStartMs = now
            windowBytes = 0
        }
    }

    companion object {
        /** A size for a message: `64 MiB`, `16 KiB` or bytes. */
        fun describe(bytes: Long): String =
            when {
                bytes >= 1024L * 1024 && bytes % (1024L * 1024) == 0L -> "${bytes / (1024L * 1024)} MiB"
                bytes >= 1024 && bytes % 1024 == 0L -> "${bytes / 1024} KiB"
                else -> "$bytes bytes"
            }

        /** The stream failure in the cause chain of [e], if there is one. */
        fun failureIn(e: Throwable): IOException? =
            e
                .causes()
                .firstOrNull { it is LimitExceeded || it is NoRoom || it is TooSlow || it is Cancelled } as IOException?
    }
}
