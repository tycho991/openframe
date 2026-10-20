package io.github.tycho991.openframe.core.http

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.SequenceInputStream

/** One part of a `multipart/form-data` request. [body] is readable only until the next [MultipartReader.next]. */
class Part(
    val name: String?,
    val filename: String?,
    val contentType: String?,
    val body: InputStream,
)

/**
 * Reads `multipart/form-data` as a stream without holding parts in memory.
 * The buffer is fixed, no matter how large the files are.
 */
class MultipartReader(
    input: InputStream,
    boundary: String,
) {
    // The first boundary stands at the start without a preceding CRLF. With a CRLF in front, the same search applies to all.
    private val input: InputStream = SequenceInputStream("\r\n".byteInputStream(), input)
    private val delimiter = "\r\n--$boundary".toByteArray(Charsets.US_ASCII)
    private val buf = ByteArray(BUFFER_SIZE)
    private var pos = 0
    private var lim = 0
    private var eof = false
    private var current: PartStream? = null
    private var finished = false
    private var started = false

    /** The next part or `null` at the end. Throws [HttpException] on a broken request. */
    fun next(): Part? {
        if (finished) return null
        if (!started) {
            started = true
            // Skip the preamble up to the first boundary.
            PartStream().let { preamble -> drain(preamble) }
        } else {
            current?.let(::drain)
        }
        // After the boundary: "--" for the end or CRLF before the next part's headers.
        ensure(2)
        if (lim - pos < 2) throw malformed("Request ends after a boundary")
        if (buf[pos] == '-'.code.toByte() && buf[pos + 1] == '-'.code.toByte()) {
            finished = true
            return null
        }
        if (buf[pos] != '\r'.code.toByte() || buf[pos + 1] != '\n'.code.toByte()) throw malformed("Expected CRLF after the boundary")
        pos += 2
        val headers = readHeaders()
        val disposition = headers["content-disposition"] ?: throw malformed("Content-Disposition is missing")
        val params = parseParams(disposition)
        val stream = PartStream()
        current = stream
        return Part(params["name"], params["filename"], headers["content-type"], stream)
    }

    private fun drain(stream: InputStream) {
        while (stream.read(SKIP) >= 0) {
            // Discard the rest of the part.
        }
    }

    private fun readHeaders(): Map<String, String> {
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine()
            if (line.isEmpty()) return headers
            val colon = line.indexOf(':')
            if (colon <= 0) throw malformed("Invalid header line")
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            if (headers.size > 32) throw malformed("Too many headers")
        }
    }

    private fun readLine(): String {
        val out = ByteArrayOutputStream()
        while (true) {
            ensure(2)
            if (lim - pos < 2) throw malformed("Headers incomplete")
            if (buf[pos] == '\r'.code.toByte() && buf[pos + 1] == '\n'.code.toByte()) {
                pos += 2
                return String(out.toByteArray(), Charsets.UTF_8)
            }
            out.write(buf[pos++].toInt())
            if (out.size() > MAX_HEADER_LINE) throw malformed("Header line too long")
        }
    }

    /** Makes sure at least [n] bytes are in the buffer, provided the stream has that many. */
    private fun ensure(n: Int) {
        if (lim - pos >= n) return
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, lim - pos)
            lim -= pos
            pos = 0
        }
        while (lim - pos < n && !eof) {
            val r = input.read(buf, lim, buf.size - lim)
            if (r < 0) eof = true else lim += r
        }
    }

    private fun indexOfDelimiter(): Int {
        val last = lim - delimiter.size
        var i = pos
        outer@ while (i <= last) {
            for (j in delimiter.indices) {
                if (buf[i + j] != delimiter[j]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return -1
    }

    /** Reads up to the next boundary and consumes it. */
    private inner class PartStream : InputStream() {
        private var ended = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (ended) return -1
            if (len == 0) return 0
            while (true) {
                ensure(delimiter.size)
                val idx = indexOfDelimiter()
                if (idx == pos) {
                    pos += delimiter.size
                    ended = true
                    return -1
                }
                val available =
                    when {
                        idx > 0 -> idx - pos
                        eof -> throw malformed("Request ends in the middle of a part")
                        else -> lim - pos - (delimiter.size - 1)
                    }
                if (available > 0) {
                    val n = minOf(available, len)
                    System.arraycopy(buf, pos, b, off, n)
                    pos += n
                    return n
                }
                // Too little in the buffer to rule out a boundary: load more.
                if (lim - pos >= buf.size) throw malformed("Buffer full")
                val before = lim - pos
                ensure(before + 1)
                if (lim - pos == before && eof) throw malformed("Request ends in the middle of a part")
            }
        }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_HEADER_LINE = 8 * 1024
        private val SKIP = ByteArray(8192)

        private fun InputStream.read(b: ByteArray) = read(b, 0, b.size)

        private fun malformed(message: String) = HttpException(400, "malformed_multipart", message)

        /** The boundary from `Content-Type: multipart/form-data; boundary=…`. */
        fun boundaryOf(contentType: String?): String? {
            if (contentType == null || !contentType.lowercase().startsWith("multipart/form-data")) return null
            return parseParams(contentType)["boundary"]?.takeIf { it.isNotEmpty() && it.length <= 200 }
        }

        internal fun parseParams(header: String): Map<String, String> {
            val result = HashMap<String, String>()
            var i = header.indexOf(';').takeIf { it >= 0 } ?: return result
            while (i < header.length) {
                i++
                while (i < header.length && header[i] == ' ') i++
                val eq = header.indexOf('=', i).takeIf { it >= 0 } ?: break
                val key = header.substring(i, eq).trim().lowercase()
                i = eq + 1
                val value =
                    if (i < header.length && header[i] == '"') {
                        val sb = StringBuilder()
                        i++
                        while (i < header.length && header[i] != '"') {
                            if (header[i] == '\\' && i + 1 < header.length) i++
                            sb.append(header[i])
                            i++
                        }
                        i++
                        sb.toString()
                    } else {
                        val end = header.indexOf(';', i).let { if (it < 0) header.length else it }
                        header.substring(i, end).trim().also { i = end }
                    }
                result[key] = value
                i = header.indexOf(';', i).let { if (it < 0) header.length else it }
            }
            return result
        }
    }
}
