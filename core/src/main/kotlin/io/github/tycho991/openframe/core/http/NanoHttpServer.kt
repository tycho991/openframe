package io.github.tycho991.openframe.core.http

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.Socket
import java.net.SocketException
import io.github.tycho991.openframe.core.http.Method as ApiMethod

/**
 * NanoHTTPD as pure transport: translates requests and responses and
 * runs the handlers on a bounded [dispatcher]. Knows no routes.
 */
class NanoHttpServer(
    host: String,
    port: Int,
    private val dispatcher: CoroutineDispatcher,
    private val handler: suspend (HttpRequest) -> HttpResponse,
) : NanoHTTPD(host, port) {
    /** The port actually bound, even if started with port 0. */
    val port: Int get() = listeningPort

    fun startServer() {
        start(SOCKET_READ_TIMEOUT_MS, true)
    }

    override fun serve(session: IHTTPSession): Response {
        val length = session.headers["content-length"]?.toLongOrNull()
        val body = BoundedInputStream(session.inputStream, length ?: 0)
        val method =
            runCatching { ApiMethod.valueOf(session.method.name) }.getOrNull()
                ?: return toNano(HttpResponse.error(HttpException(405, "method_not_allowed", "Method not supported")), null)
        val request =
            HttpRequest(
                method = method,
                path = session.uri,
                query = HttpRequest.parseQuery(session.queryParameterString),
                headers = session.headers,
                contentLength = length,
                body = body,
            )
        val response =
            runBlocking {
                withContext(dispatcher) { handler(request) }
            }
        val nano = toNano(response, request)
        // Unread bodies would disturb the next request on the same connection. Discard instead of
        // closing right away, otherwise a TCP reset makes the client drop the response already sent.
        // A client that announced a body and does not send it must not hold the answer back for the
        // whole read timeout, so waiting for the rest is limited to a short time.
        val connection = ConnectionInput.current()
        connection?.setReadTimeout(DRAIN_TIMEOUT_MS)
        val drained =
            try {
                body.drain(MAX_DRAIN_BYTES)
            } catch (e: IOException) {
                false
            }
        connection?.setReadTimeout(SOCKET_READ_TIMEOUT_MS)
        if (!drained) nano.closeConnection(true)
        return nano
    }

    /** Headers and body go out separately. Without `TCP_NODELAY` that costs about 40 ms per response with delayed ACK. */
    override fun createClientHandler(
        finalAccept: Socket,
        inputStream: InputStream,
    ): ClientHandler {
        runCatching { finalAccept.tcpNoDelay = true }
        return super.createClientHandler(finalAccept, ConnectionInput(finalAccept, inputStream))
    }

    override fun useGzipWhenAccepted(r: Response): Boolean {
        val type = r.mimeType?.lowercase() ?: return false
        return type.startsWith("text/") || "json" in type || "javascript" in type || "svg" in type
    }

    private fun toNano(
        response: HttpResponse,
        request: HttpRequest?,
    ): Response {
        val nano =
            when (response) {
                is HttpResponse.Json -> {
                    newFixedLengthResponse(status(response.status), JSON_TYPE, response.body)
                }

                is HttpResponse.Empty -> {
                    newFixedLengthResponse(status(response.status), null, null, 0)
                }

                is HttpResponse.StreamBody -> {
                    if (response.length != null) {
                        newFixedLengthResponse(status(response.status), response.contentType, response.open(), response.length)
                    } else {
                        newChunkedResponse(status(response.status), response.contentType, response.open())
                    }
                }

                is HttpResponse.FileBody -> {
                    fileResponse(response, request?.header("range"))
                }
            }
        response.headers.forEach { (k, v) -> nano.addHeader(k, v) }
        return nano
    }

    private fun fileResponse(
        response: HttpResponse.FileBody,
        rangeHeader: String?,
    ): Response {
        val total = response.file.length()
        val range = if (response.status == 200) rangeHeader?.let { parseRange(it, total) } else null
        val nano =
            when {
                range == null -> {
                    newFixedLengthResponse(status(response.status), response.contentType, response.file.inputStream(), total)
                }

                range.isEmpty() -> {
                    newFixedLengthResponse(status(416), null, null, 0).also { it.addHeader("Content-Range", "bytes */$total") }
                }

                else -> {
                    val raf = RandomAccessFile(response.file, "r").also { it.seek(range.first) }
                    val stream =
                        BoundedInputStream(
                            java.nio.channels.Channels
                                .newInputStream(raf.channel),
                            range.last - range.first + 1,
                        )
                    newFixedLengthResponse(status(206), response.contentType, stream, range.last - range.first + 1).also {
                        it.addHeader("Content-Range", "bytes ${range.first}-${range.last}/$total")
                    }
                }
            }
        nano.addHeader("Accept-Ranges", "bytes")
        return nano
    }

    companion object {
        const val SOCKET_READ_TIMEOUT_MS = 30_000

        /** How long to wait for the rest of a body that is not needed any more. */
        const val DRAIN_TIMEOUT_MS = 2_000
        private const val MAX_DRAIN_BYTES = 8L * 1024 * 1024
        private const val JSON_TYPE = "application/json; charset=utf-8"

        /** A single range `bytes=a-b`, `bytes=a-` or `bytes=-n`. An empty range means 416. */
        internal fun parseRange(
            header: String,
            total: Long,
        ): LongRange? {
            if (!header.startsWith("bytes=") || ',' in header) return null
            val spec = header.removePrefix("bytes=").trim()
            val start = spec.substringBefore('-').trim()
            val end = spec.substringAfter('-').trim()
            val range =
                when {
                    start.isEmpty() -> {
                        val suffix = end.toLongOrNull() ?: return null
                        if (suffix <= 0) return LongRange.EMPTY
                        maxOf(0, total - suffix) until total
                    }

                    else -> {
                        val from = start.toLongOrNull() ?: return null
                        val to = if (end.isEmpty()) total - 1 else (end.toLongOrNull() ?: return null).coerceAtMost(total - 1)
                        from..to
                    }
                }
            return if (range.first >= total || range.first > range.last) LongRange.EMPTY else range
        }

        private fun status(code: Int): Response.IStatus =
            Response.Status.lookup(code) ?: object : Response.IStatus {
                override fun getDescription() = "$code ${REASONS[code] ?: "Status"}"

                override fun getRequestStatus() = code
            }

        private val REASONS =
            mapOf(
                405 to "Method Not Allowed",
                413 to "Payload Too Large",
                429 to "Too Many Requests",
                507 to "Insufficient Storage",
            )
    }
}

/**
 * The input of a connection with its socket, so that the read timeout can be shortened for a while.
 * NanoHTTPD wraps it in a buffer of its own and reads and serves a connection on one thread, hence
 * [current] finds the connection that the calling thread is reading from.
 */
private class ConnectionInput(
    private val socket: Socket,
    input: InputStream,
) : FilterInputStream(input) {
    override fun read(): Int {
        current.set(this)
        return super.read()
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        current.set(this)
        return super.read(b, off, len)
    }

    override fun close() {
        if (current.get() === this) current.remove()
        super.close()
    }

    fun setReadTimeout(millis: Int) {
        try {
            socket.soTimeout = millis
        } catch (e: SocketException) {
            // The connection is closed anyway.
        }
    }

    companion object {
        private val current = ThreadLocal<ConnectionInput?>()

        /** The connection the calling thread reads from, `null` outside a request. */
        fun current(): ConnectionInput? = current.get()
    }
}

/** Reads at most [limit] bytes from [input] and leaves the rest of the connection untouched. */
internal class BoundedInputStream(
    input: InputStream,
    private var limit: Long,
) : FilterInputStream(input) {
    override fun read(): Int {
        if (limit <= 0) return -1
        val b = super.read()
        if (b >= 0) limit--
        return b
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (limit <= 0) return -1
        val n = super.read(b, off, minOf(len.toLong(), limit).toInt())
        if (n > 0) limit -= n
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(minOf(n, limit))
        limit -= skipped
        return skipped
    }

    override fun available(): Int = minOf(super.available().toLong(), limit).toInt()

    /** The connection belongs to NanoHTTPD and is not closed here. */
    override fun close() {}

    override fun markSupported() = false

    /** Reads away up to [max] remaining bytes. `false` if data is left afterwards. */
    fun drain(max: Long): Boolean {
        var budget = max
        val buf = ByteArray(8192)
        while (limit > 0 && budget > 0) {
            val n = read(buf, 0, minOf(buf.size.toLong(), budget).toInt())
            if (n < 0) break
            budget -= n
        }
        return limit <= 0
    }
}
