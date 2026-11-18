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
        return send(method, path, publisher, headers, contentType)
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
        )

    private fun send(
        method: String,
        path: String,
        publisher: JdkRequest.BodyPublisher,
        headers: Map<String, String>,
        contentType: String?,
    ): Res {
        val builder = JdkRequest.newBuilder(URI.create(base + path)).method(method, publisher)
        contentType?.let { builder.header("Content-Type", it) }
        headers.forEach { (k, v) -> builder.header(k, v) }
        val res = Res(client.send(builder.build(), JdkResponse.BodyHandlers.ofByteArray()))
        if (checkContract && path.startsWith("/api/")) {
            val type = res.header("content-type")
            val text = if (type?.startsWith("application/json") == true || res.bytes().isEmpty()) res.body() else ""
            Contract.check(method, path, res.statusCode(), type, text)
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
        val (method, target) = request.substringBefore("\r\n").split(' ').let { it[0] to it[1] }
        if (checkContract && target.startsWith("/api/")) {
            Contract.check(method, target, res.status, res.headers["content-type"], res.body)
        }
        return res
    }

    /** The port of the server. */
    fun port(): Int = base.substringAfterLast(':').toInt()

    override fun close() {
        server?.stop()
    }
}

/** An answer read from a socket: status, headers with lower-case names, and the body as text. */
class RawRes(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
) {
    companion object {
        fun parse(bytes: ByteArray): RawRes {
            val text = bytes.toString(Charsets.UTF_8)
            val head = text.substringBefore("\r\n\r\n")
            val lines = head.split("\r\n")
            val status = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: error("No status line in: $text")
            val headers = lines.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            return RawRes(status, headers, text.substringAfter("\r\n\r\n", ""))
        }
    }
}
