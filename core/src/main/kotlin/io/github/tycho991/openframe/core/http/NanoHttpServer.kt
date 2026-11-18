package io.github.tycho991.openframe.core.http

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.Socket
import io.github.tycho991.openframe.core.http.Method as ApiMethod

/**
 * NanoHTTPD as pure transport: translates requests and responses and
 * runs the handlers on a bounded [dispatcher]. Knows no routes.
 *
 * Connections are bounded by [ServerLimits]: in number, per remote address and
 * in time (design decisions 1 to 3 of harden-lan-server). A JSON body up to
 * 64 KiB is read on the connection's thread before the handler gets a slot, so
 * slow bodies hold no handler.
 */
class NanoHttpServer internal constructor(
    host: String,
    port: Int,
    private val dispatcher: CoroutineDispatcher,
    private val limits: ServerLimits,
    startThread: ((Runnable, String) -> Unit)?,
    private val handler: suspend (HttpRequest) -> HttpResponse,
) : NanoHTTPD(host, port) {
    constructor(
        host: String,
        port: Int,
        dispatcher: CoroutineDispatcher,
        handler: suspend (HttpRequest) -> HttpResponse,
        limits: ServerLimits = ServerLimits(),
    ) : this(host, port, dispatcher, limits, null, handler)

    private val runner =
        if (startThread != null) {
            BoundedAsyncRunner(limits, { "http-$listeningPort" }, startThread)
        } else {
            BoundedAsyncRunner(limits, { "http-$listeningPort" })
        }

    init {
        setAsyncRunner(runner)
        setServerSocketFactory { BackoffServerSocket(limits.acceptBackoffMs) }
    }

    /** The port actually bound, even if started with port 0. */
    val port: Int get() = listeningPort

    /** Connections currently open, for tests. */
    internal val connections: Int get() = runner.size

    fun startServer() {
        start(limits.idleTimeoutMs, true)
    }

    override fun serve(session: IHTTPSession): Response {
        val connection = current.get()
        connection?.beginRequest()
        val length = session.headers["content-length"]?.toLongOrNull()
        val json = isJson(session.headers["content-type"])
        var unread: BoundedInputStream? = null
        val body: InputStream =
            if (json && length != null && length in 1..EARLY_JSON_BYTES) {
                connection?.beginBody(limits.jsonBodyTimeoutMs)
                try {
                    ByteArrayInputStream(readUpTo(session.inputStream, length.toInt()))
                } catch (e: IOException) {
                    return finish(toNano(timeout(), null), connection, close = true)
                }
            } else {
                if ((length ?: 0) > 0) connection?.beginBody(if (json) limits.jsonBodyTimeoutMs else null)
                BoundedInputStream(session.inputStream, length ?: 0).also { unread = it }
            }
        val method =
            runCatching { ApiMethod.valueOf(session.method.name) }.getOrNull()
                ?: return finish(
                    toNano(HttpResponse.error(HttpException(405, "method_not_allowed", "Method not supported")), null),
                    connection,
                    close = true,
                )
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
        // A body that did not arrive in time leaves the connection in an unknown state.
        var close = response.status == 408
        // Unread bodies would disturb the next request on the same connection. Discard instead of
        // closing right away, otherwise a TCP reset makes the client drop the response already sent.
        // A client that announced a body and does not send it must not hold the answer back for the
        // whole read timeout, so waiting for the rest has a deadline of its own.
        val rest = unread
        if (rest != null && !close) {
            connection?.beginDrain()
            val drained =
                try {
                    rest.drain(MAX_DRAIN_BYTES)
                } catch (e: IOException) {
                    false
                }
            if (!drained) close = true
        }
        return finish(nano, connection, close)
    }

    private fun finish(
        nano: Response,
        connection: ConnectionInput?,
        close: Boolean,
    ): Response {
        if (close) nano.closeConnection(true)
        connection?.endRequest()
        return nano
    }

    private fun timeout(): HttpResponse =
        HttpResponse
            .error(HttpException(408, "request_timeout", "The request body did not arrive in time"))
            .withHeaders(HttpPipeline.SECURITY_HEADERS)

    /** Headers and body go out separately. Without `TCP_NODELAY` that costs about 40 ms per response with delayed ACK. */
    override fun createClientHandler(
        finalAccept: Socket,
        inputStream: InputStream,
    ): ClientHandler {
        runCatching { finalAccept.tcpNoDelay = true }
        return Connection(finalAccept, ConnectionInput(finalAccept, inputStream, limits))
    }

    /** A connection with its address and input, for the runner's limits; [serve] finds the input on its thread. */
    private inner class Connection(
        socket: Socket,
        override val input: ConnectionInput,
    ) : ClientHandler(input, socket),
        BoundedAsyncRunner.Tracked {
        override val address: InetAddress? = socket.inetAddress

        override fun run() {
            current.set(input)
            try {
                super.run()
            } finally {
                current.remove()
            }
        }
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
        /** JSON bodies up to this size are read before the handler gets a slot. */
        const val EARLY_JSON_BYTES = 64L * 1024
        private const val MAX_DRAIN_BYTES = 8L * 1024 * 1024
        private const val JSON_TYPE = "application/json; charset=utf-8"

        /** The input of the connection whose thread calls, set for the connection's whole life. */
        private val current = ThreadLocal<ConnectionInput?>()

        private fun isJson(contentType: String?) = contentType?.substringBefore(';')?.trim()?.lowercase() == "application/json"

        /** Up to [length] bytes, fewer if the stream ends first. */
        private fun readUpTo(
            input: InputStream,
            length: Int,
        ): ByteArray {
            val buf = ByteArray(length)
            var off = 0
            while (off < length) {
                val n = input.read(buf, off, length - off)
                if (n < 0) break
                off += n
            }
            return if (off == length) buf else buf.copyOf(off)
        }

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
                408 to "Request Timeout",
                413 to "Payload Too Large",
                421 to "Misdirected Request",
                429 to "Too Many Requests",
                507 to "Insufficient Storage",
            )
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
