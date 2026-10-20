package io.github.tycho991.openframe.core.http

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.random.Random

class MultipartReaderTest {
    private val boundary = "----openframe7MA4YWxkTrZu0gW"

    private fun body(vararg parts: Triple<String, String?, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("preamble\r\n".toByteArray())
        for ((name, filename, data) in parts) {
            out.write("--$boundary\r\n".toByteArray())
            val fn = if (filename != null) "; filename=\"$filename\"" else ""
            out.write("Content-Disposition: form-data; name=\"$name\"$fn\r\n".toByteArray())
            if (filename != null) out.write("Content-Type: image/jpeg\r\n".toByteArray())
            out.write("\r\n".toByteArray())
            out.write(data)
            out.write("\r\n".toByteArray())
        }
        out.write("--$boundary--\r\n".toByteArray())
        return out.toByteArray()
    }

    /** Delivers the data in small, irregular chunks like a slow network. */
    private class Trickle(
        private val data: ByteArray,
    ) : InputStream() {
        private var i = 0
        private val rnd = Random(42)

        override fun read(): Int = if (i < data.size) data[i++].toInt() and 0xff else -1

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (i >= data.size) return -1
            val n = minOf(len, data.size - i, 1 + rnd.nextInt(700))
            System.arraycopy(data, i, b, off, n)
            i += n
            return n
        }
    }

    @Test
    fun `Fields and files`() {
        val image = Random(1).nextBytes(300_000)
        // Bytes that look like the start of a boundary must not interfere.
        val tricky = "a\r\n--${boundary.dropLast(1)}X\r\n-".toByteArray() + image + "\r\n--".toByteArray()
        val reader =
            MultipartReader(
                Trickle(
                    body(
                        Triple("album", null, "family".toByteArray()),
                        Triple("file", "a b.jpg", tricky),
                        Triple("file", "empty.jpg", ByteArray(0)),
                    ),
                ),
                boundary,
            )
        val album = reader.next()!!
        assertEquals("album", album.name)
        assertNull(album.filename)
        assertEquals("family", album.body.readBytes().toString(Charsets.UTF_8))
        val file = reader.next()!!
        assertEquals("a b.jpg", file.filename)
        assertEquals("image/jpeg", file.contentType)
        assertArrayEquals(tricky, file.body.readBytes())
        val empty = reader.next()!!
        assertEquals(0, empty.body.readBytes().size)
        assertNull(reader.next())
    }

    @Test
    fun `Unread parts are skipped`() {
        val reader =
            MultipartReader(body(Triple("a", "1.jpg", ByteArray(100_000)), Triple("b", null, "x".toByteArray())).inputStream(), boundary)
        reader.next()
        assertEquals(
            "x",
            reader
                .next()!!
                .body
                .readBytes()
                .toString(Charsets.UTF_8),
        )
        assertNull(reader.next())
    }

    @Test
    fun `Truncated request`() {
        val full = body(Triple("file", "a.jpg", ByteArray(10_000)))
        val reader = MultipartReader(full.copyOf(full.size - 100).inputStream(), boundary)
        val part = reader.next()!!
        assertThrows<HttpException> { part.body.readBytes() }
    }

    @Test
    fun `Boundary and parameters`() {
        assertEquals("abc", MultipartReader.boundaryOf("multipart/form-data; boundary=abc"))
        assertEquals("a;b", MultipartReader.boundaryOf("multipart/form-data; boundary=\"a;b\""))
        assertNull(MultipartReader.boundaryOf("application/json"))
        assertEquals(
            mapOf("name" to "file", "filename" to "Ü \"x\".jpg"),
            MultipartReader.parseParams("form-data; name=\"file\"; filename=\"Ü \\\"x\\\".jpg\""),
        )
    }
}
