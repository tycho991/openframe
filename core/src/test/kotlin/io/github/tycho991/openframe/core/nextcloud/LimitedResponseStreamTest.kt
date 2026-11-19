package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.testing.TestClock
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Duration
import java.time.Instant

/** Design decision 1 of `nextcloud-hardening`: every body is bounded in bytes, total time and throughput, and can be cancelled. */
class LimitedResponseStreamTest {
    private val clock = TestClock()

    /** A body that moves [clock] by [stepMs] before each read and hands out [chunk] bytes at a time. */
    private fun body(
        size: Int,
        chunk: Int = size,
        stepMs: Long = 0,
    ): InputStream =
        object : InputStream() {
            private val inner = ByteArrayInputStream(ByteArray(size) { (it % 251).toByte() })

            override fun read(): Int = inner.read()

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int {
                clock.advance(Duration.ofMillis(stepMs))
                return inner.read(b, off, minOf(len, chunk))
            }
        }

    private fun limited(
        input: InputStream,
        maxBytes: Long = Long.MAX_VALUE,
        totalMs: Long = Long.MAX_VALUE / 2,
        roomBytes: Long = Long.MAX_VALUE,
        cancelled: () -> Boolean = { false },
    ) = LimitedResponseStream(input, clock, "The body", maxBytes, totalMs, roomBytes, cancelled)

    @Test
    fun `A body of exactly the cap passes, one byte more fails`() {
        assertEquals(1000, limited(body(1000), maxBytes = 1000).readBytes().size)
        val failure = assertThrows<LimitedResponseStream.LimitExceeded> { limited(body(1001), maxBytes = 1000).readBytes() }
        assertTrue(failure.message!!.contains("larger than 1000 bytes"), failure.message)
        assertThrows<LimitedResponseStream.LimitExceeded> { limited(body(2 * 1024 * 1024), maxBytes = 1024 * 1024).readBytes() }
    }

    @Test
    fun `The content passes unchanged`() {
        val expected = ByteArray(100_000) { (it % 251).toByte() }
        assertArrayEquals(expected, limited(body(100_000, chunk = 777), maxBytes = 100_000).readBytes())
    }

    @Test
    fun `More than the free storage allows is no room, not too large`() {
        assertThrows<LimitedResponseStream.NoRoom> { limited(body(5000), maxBytes = 10_000, roomBytes = 4000).readBytes() }
    }

    @Test
    fun `A stall below 16 KiB per 60 seconds fails`() {
        // 1 KiB every 10 seconds: 6 KiB per minute.
        val failure = assertThrows<LimitedResponseStream.TooSlow> { limited(body(100 * 1024, chunk = 1024, stepMs = 10_000)).readBytes() }
        assertTrue(failure.message!!.contains("too slowly"), failure.message)
        assertTrue(clock.monotonic in 60_000L..80_000L, "it fails in the first window, at ${clock.monotonic} ms")
    }

    @Test
    fun `A steady trickle above the minimum still ends at the total time`() {
        // 4 KiB every 10 seconds: 24 KiB per minute, above the minimum; 5 minutes total.
        val failure =
            assertThrows<LimitedResponseStream.TooSlow> {
                limited(body(10 * 1024 * 1024, chunk = 4096, stepMs = 10_000), totalMs = 5 * 60_000L).readBytes()
            }
        assertTrue(failure.message!!.contains("longer than 5 minutes"), failure.message)
        // The same trickle within the total time is read to the end.
        assertEquals(40 * 4096, limited(body(40 * 4096, chunk = 4096, stepMs = 5_000), totalMs = 5 * 60_000L).readBytes().size)
    }

    @Test
    fun `A cancel between reads ends the body`() {
        var reads = 0
        val stream = limited(body(10_000, chunk = 100), cancelled = { reads >= 3 })
        val buffer = ByteArray(100)
        assertThrows<LimitedResponseStream.Cancelled> {
            while (stream.read(buffer) >= 0) reads++
        }
        assertEquals(3, reads)
    }

    @Test
    fun `A jump of the wall clock does not count as slow`() {
        val input =
            object : InputStream() {
                private val inner = ByteArrayInputStream(ByteArray(64 * 1024))

                override fun read(): Int = inner.read()

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    // An NTP sync moves the wall clock by a day; the monotonic time does not move.
                    clock.instant = clock.instant + Duration.ofDays(1)
                    return inner.read(b, off, minOf(len, 1024))
                }
            }
        assertEquals(64 * 1024, limited(input, totalMs = 60_000L).readBytes().size)
        assertTrue(clock.instant.isAfter(Instant.parse("2026-06-01T00:00:00Z")))
    }

    @Test
    fun `The failures are found in the cause chain of a parser`() {
        val inner = LimitedResponseStream.TooSlow("slow")
        val wrapped = org.xml.sax.SAXException(java.io.IOException("outer", inner))
        assertEquals(inner, LimitedResponseStream.failureIn(wrapped))
    }
}
