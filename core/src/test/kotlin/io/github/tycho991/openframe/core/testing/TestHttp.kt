package io.github.tycho991.openframe.core.testing

import io.github.tycho991.openframe.core.http.HttpRequest
import io.github.tycho991.openframe.core.http.HttpResponse
import io.github.tycho991.openframe.core.http.NanoHttpServer
import io.github.tycho991.openframe.core.http.ServerLimits
import kotlinx.coroutines.Dispatchers
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest as JdkRequest
import java.net.http.HttpResponse as JdkResponse

/** A test's response: status, headers and body as bytes and text. */
class Res(
    private val raw: JdkResponse<ByteArray>,
) {
    fun statusCode(): Int = raw.statusCode()

    fun headers(): HttpHeaders = raw.headers()

    fun bytes(): ByteArray = raw.body()

    fun body(): String = raw.body().toString(Charsets.UTF_8)

    fun uri(): URI = raw.uri()

    fun header(name: String): String? = raw.headers().firstValue(name).orElse(null)
}

/**
 * An HTTP client for tests, either with a server of its own on a free
 * port or for one that is already running. With [checkContract] every
 * response under `/api/` is checked against the API contract.
 */
class TestHttp private constructor(
    private val server: NanoHttpServer?,
    private val baseUrl: String?,
    private val checkContract: Boolean,
) : AutoCloseable {
    constructor(
        handler: suspend (HttpRequest) -> HttpResponse,
        checkContract: Boolean = false,
        limits: ServerLimits = ServerLimits(),
    ) : this(
        NanoHttpServer("127.0.0.1", 0, Dispatchers.IO.limitedParallelism(4), handler, limits).also { it.startServer() },
        null,
        checkContract,
    )

    /** Client for a server that is already running, for example the one of a [io.github.tycho991.openframe.core.FrameCore]. */
    constructor(baseUrl: String, checkContract: Boolean = true) : this(null, baseUrl, checkContract)

    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    val base: String get() = baseUrl ?: "http://127.0.0.1:${server!!.port}"

    fun get(
        path: String,
        headers: Map<String, String> = emptyMap(),
    ) = request("GET", path, headers = headers)

    fun request(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        contentType: String? = if (body != null) "application/json" else null,
    ): Res = requestBytes(method, path, body?.toByteArray(), headers, contentType)

    fun requestBytes(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        contentType: String? = null,
    ): Res {
        val publisher = if (body != null) JdkRequest.BodyPublishers.ofByteArray(body) else JdkRequest.BodyPublishers.noBody()
        val json = body != null && contentType?.startsWith("application/json") == true
        return send(method, path, publisher, headers, contentType, if (json) body!!.toString(Charsets.UTF_8) else null)
    }

    /** Sends the body as a stream of known length without holding it in memory. */
    fun stream(
        method: String,
        path: String,
        contentType: String,
        length: Long,
        headers: Map<String, String> = emptyMap(),
        body: () -> InputStream,
    ): Res =
        send(
            method,
            path,
            JdkRequest.BodyPublishers.fromPublisher(JdkRequest.BodyPublishers.ofInputStream(body), length),
            headers,
            contentType,
            null,
        )

    private fun send(
        method: String,
        path: String,
        publisher: JdkRequest.BodyPublisher,
        headers: Map<String, String>,
        contentType: String?,
        jsonBody: String?,
    ): Res {
        val builder = JdkRequest.newBuilder(URI.create(base + path)).method(method, publisher)
        contentType?.let { builder.header("Content-Type", it) }
        headers.forEach { (k, v) -> builder.header(k, v) }
        val res = Res(client.send(builder.build(), JdkResponse.BodyHandlers.ofByteArray()))
        if (checkContract && path.startsWith("/api/")) {
            val type = res.header("content-type")
            Contract.check(
                method,
                path,
                res.statusCode(),
                type,
                if (isText(type) || res.bytes().isEmpty()) res.body() else "",
                headers = res.headers().map().mapKeys { it.key.lowercase() },
                bodyBytes = res.bytes().size,
                requestBody = jsonBody,
            )
        }
        return res
    }

    /**
     * Sends [request] as it is over a socket of its own, for requests that the JDK client cannot send, such as one
     * without `Host`. The request should end the connection (HTTP/1.0 or `Connection: close`). The answer is checked
     * against the contract like the others.
     */
    fun raw(
        request: String,
        timeoutMs: Int = 10_000,
        socket: java.net.Socket = java.net.Socket("127.0.0.1", port()),
    ): RawRes {
        val res =
            socket.use { s ->
                s.soTimeout = timeoutMs
                s.getOutputStream().write(request.toByteArray())
                s.getOutputStream().flush()
                RawRes.parse(s.getInputStream().readBytes())
            }
        checkRaw(request, res)
        return res
    }

    /**
     * Sends [requests] one after the other on one keep-alive connection and reads one answer for each. A request whose
     * method is HEAD gets an answer without a body, as HTTP says; any bytes it sent anyway end up in the next answer.
     */
    fun rawSequence(
        requests: List<String>,
        timeoutMs: Int = 10_000,
    ): List<RawRes> =
        java.net.Socket("127.0.0.1", port()).use { s ->
            s.soTimeout = timeoutMs
            val input = java.io.BufferedInputStream(s.getInputStream())
            requests.map { request ->
                s.getOutputStream().write(request.toByteArray())
                s.getOutputStream().flush()
                RawRes.read(input, withBody = !request.startsWith("HEAD ")).also { checkRaw(request, it) }
            }
        }

    private fun checkRaw(
        request: String,
        res: RawRes,
    ) {
        val (method, target) = request.substringBefore("\r\n").split(' ').let { it[0] to it[1] }
        if (!checkContract || !target.startsWith("/api/")) return
        val requestHead = request.substringBefore("\r\n\r\n").lowercase()
        val json = "content-type: application/json" in requestHead
        Contract.check(
            method,
            target,
            res.status,
            res.headers["content-type"],
            res.body,
            headers = res.headerLists,
            bodyBytes = res.bodyBytes,
            requestBody = if (json) request.substringAfter("\r\n\r\n", "").ifEmpty { null } else null,
        )
    }

    private fun isText(type: String?): Boolean {
        val t = type?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return t.startsWith("text/") || t == "application/json"
    }

    /** The port of the server. */
    fun port(): Int = base.substringAfterLast(':').toInt()

    override fun close() {
        server?.stop()
    }
}

/**
 * An answer read from a socket: status, headers with lower-case names ([headers] keeps the last of repeated ones,
 * [headerLists] all of them), and the body as text and its length in bytes (decoded if it was chunked).
 */
class RawRes(
    val status: Int,
    val headerLists: Map<String, List<String>>,
    val body: String,
    val bodyBytes: Int = body.toByteArray().size,
) {
    val headers: Map<String, String> = headerLists.mapValues { it.value.last() }

    companion object {
        fun parse(bytes: ByteArray): RawRes {
            val text = bytes.toString(Charsets.UTF_8)
            val head = text.substringBefore("\r\n\r\n")
            val (status, headers) = parseHead(head, text)
            return RawRes(status, headers, text.substringAfter("\r\n\r\n", ""))
        }

        /** Reads exactly one answer from [input]: by `Content-Length`, chunked, or none when [withBody] is false. */
        fun read(
            input: java.io.InputStream,
            withBody: Boolean,
        ): RawRes {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) error("Connection closed in the head after: $head")
                head.append(b.toChar())
            }
            val (status, headers) = parseHead(head.removeSuffix("\r\n\r\n").toString(), head.toString())
            if (!withBody || status == 204 || status == 304) return RawRes(status, headers, "", 0)
            val body = java.io.ByteArrayOutputStream()
            val length = headers["content-length"]?.singleOrNull()?.toLongOrNull()
            when {
                headers["transfer-encoding"]?.any { it.equals("chunked", true) } == true -> {
                    while (true) {
                        val size = readLine(input).substringBefore(';').trim().toInt(16)
                        if (size == 0) {
                            while (readLine(input).isNotEmpty()) Unit
                            break
                        }
                        body.write(readExactly(input, size))
                        readLine(input)
                    }
                }

                length != null && length >= 0 -> {
                    body.write(readExactly(input, length.toInt()))
                }

                else -> {
                    error("Answer without a usable length: $headers")
                }
            }
            val bytes = body.toByteArray()
            return RawRes(status, headers, bytes.toString(Charsets.UTF_8), bytes.size)
        }

        private fun parseHead(
            head: String,
            all: String,
        ): Pair<Int, Map<String, List<String>>> {
            val lines = head.split("\r\n")
            val status =
                lines
                    .first()
                    .split(' ')
                    .getOrNull(1)
                    ?.toIntOrNull() ?: error("No status line in: $all")
            val headers =
                lines
                    .drop(1)
                    .filter { it.isNotEmpty() }
                    .groupBy({ it.substringBefore(':').trim().lowercase() }, { it.substringAfter(':').trim() })
            return status to headers
        }

        private fun readLine(input: java.io.InputStream): String {
            val line = StringBuilder()
            while (!line.endsWith("\r\n")) {
                val b = input.read()
                if (b < 0) error("Connection closed in a chunk header")
                line.append(b.toChar())
            }
            return line.removeSuffix("\r\n").toString()
        }

        private fun readExactly(
            input: java.io.InputStream,
            n: Int,
        ): ByteArray {
            val out = ByteArray(n)
            var off = 0
            while (off < n) {
                val k = input.read(out, off, n - off)
                if (k < 0) error("Connection closed after $off of $n body bytes")
                off += k
            }
            return out
        }
    }
}
