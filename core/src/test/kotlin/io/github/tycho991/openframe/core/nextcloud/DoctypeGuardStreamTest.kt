package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.sources.SourceException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream

class DoctypeGuardStreamTest {
    /** Gives at most one byte per read, as a slow network might: every state change happens across reads. */
    private class OneByteAtATime(
        private val bytes: ByteArray,
    ) : InputStream() {
        private var position = 0

        override fun read(): Int = if (position < bytes.size) bytes[position++].toInt() and 0xFF else -1

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (len == 0) return 0
            val next = read()
            if (next < 0) return -1
            b[off] = next.toByte()
            return 1
        }
    }

    /** Reads [bytes] through the guard in chunks of [chunk] and returns what the guard let through, or throws. */
    private fun passed(
        bytes: ByteArray,
        chunk: Int,
        sink: ByteArrayOutputStream = ByteArrayOutputStream(),
    ): ByteArray {
        val source = if (chunk == 1) OneByteAtATime(bytes) else bytes.inputStream()
        val guard = DoctypeGuardStream(source)
        val buffer = ByteArray(maxOf(chunk, 1))
        while (true) {
            val n = guard.read(buffer, 0, buffer.size)
            if (n < 0) return sink.toByteArray()
            sink.write(buffer, 0, n)
        }
    }

    private val listing =
        """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/a.jpg</d:href></d:response></d:multistatus>"""

    @Test
    fun `A listing with a declaration, comments and a byte order mark passes unchanged`() {
        val bytes =
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                """<?xml version="1.0" encoding="UTF-8"?>
                |<!-- a comment that mentions <!DOCTYPE x> and -- dashes - -->
                |<?pi with ? and > inside?>
                |$listing
                """.trimMargin().toByteArray()
        for (chunk in listOf(1, 3, 8192)) assertArrayEquals(bytes, passed(bytes, chunk), "chunk $chunk")
    }

    @Test
    fun `After the first element the bytes are not looked at`() {
        // Not well-formed, but the parser's to refuse: a document type cannot follow the first element.
        val bytes = """<d:multistatus xmlns:d="DAV:"><!DOCTYPE x>${"é".repeat(10)}</d:multistatus>""".toByteArray()
        assertArrayEquals(bytes, passed(bytes, 8192))
    }

    @Test
    fun `A DOCTYPE is refused before any byte of its internal subset passes`() {
        val doc = """<?xml version="1.0"?><!DOCTYPE d:multistatus [<!ENTITY a "aaaaaaaaaa">]>$listing"""
        for (chunk in listOf(1, 2, 7, 8192)) {
            val sink = ByteArrayOutputStream()
            val refused = assertThrows(DoctypeGuardStream.Refused::class.java) { passed(doc.toByteArray(), chunk, sink) }
            assertEquals(SourceException.Kind.PROTOCOL, refused.failure.kind)
            assertEquals(PropfindParser.DOCTYPE_REFUSED, refused.failure.message)
            assertFalse(sink.toString(Charsets.UTF_8.name()).contains("DOCTYPE"), "chunk $chunk let through: $sink")
            assertFalse(sink.toString(Charsets.UTF_8.name()).contains("ENTITY"), "chunk $chunk let through: $sink")
        }
    }

    @Test
    fun `Other markup declarations before the first element are refused as well`() {
        for (doc in listOf("<!ENTITY a \"b\">$listing", "<![CDATA[x]]>$listing", "<!- x -->$listing", "<!doctype x>$listing")) {
            val refused = assertThrows(DoctypeGuardStream.Refused::class.java) { passed(doc.toByteArray(), 8192) }
            assertEquals(PropfindParser.DOCTYPE_REFUSED, refused.failure.message, doc)
        }
    }

    @Test
    fun `A listing in UTF-16 is refused, with or without a byte order mark`() {
        val utf16 =
            listOf(
                "\uFEFF$listing".toByteArray(Charsets.UTF_16BE),
                "\uFEFF$listing".toByteArray(Charsets.UTF_16LE),
                listing.toByteArray(Charsets.UTF_16BE),
                listing.toByteArray(Charsets.UTF_16LE),
                // A broken UTF-8 byte order mark.
                byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0x3C) + listing.toByteArray(),
            )
        for (bytes in utf16) {
            val refused = assertThrows(DoctypeGuardStream.Refused::class.java) { passed(bytes, 8192) }
            assertEquals(DoctypeGuardStream.NOT_UTF8, refused.failure.message)
        }
    }

    @Test
    fun `The parser reports the guard's refusal with the handler's text`() {
        val nested =
            """<?xml version="1.0"?><!DOCTYPE d:multistatus [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">]>""" +
                """<d:multistatus xmlns:d="DAV:"><d:response><d:href>&b;</d:href></d:response></d:multistatus>"""
        for (feature in listOf(true, false)) {
            val failure =
                assertThrows(SourceException::class.java) {
                    PropfindParser.parse(OneByteAtATime(nested.toByteArray()), refuseDoctypeByFeature = feature)
                }
            assertEquals(SourceException.Kind.PROTOCOL, failure.kind)
            assertEquals(PropfindParser.DOCTYPE_REFUSED, failure.message)
        }
        val utf16 = assertThrows(SourceException::class.java) { PropfindParser.parse(listing.toByteArray(Charsets.UTF_16).inputStream()) }
        assertEquals(DoctypeGuardStream.NOT_UTF8, utf16.message)
    }
}
