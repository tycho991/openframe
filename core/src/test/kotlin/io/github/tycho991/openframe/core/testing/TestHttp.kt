package io.github.tycho991.openframe.core.testing

import io.github.tycho991.openframe.core.http.HttpRequest
import io.github.tycho991.openframe.core.http.HttpResponse
import io.github.tycho991.openframe.core.http.NanoHttpServer
import kotlinx.coroutines.Dispatchers
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest as JdkRequest
import java.net.http.HttpResponse as JdkResponse

/** Antwort eines Tests: Status, Header und Rumpf als Bytes und Text. */
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
 * Ein HTTP-Client für Tests, entweder mit eigenem Server auf einem freien
 * Port oder für einen schon laufenden. Mit [checkContract] wird jede
 * Antwort unter `/api/` gegen den API-Vertrag geprüft.
 */
class TestHttp private constructor(
    private val server: NanoHttpServer?,
    private val baseUrl: String?,
    private val checkContract: Boolean,
) : AutoCloseable {
    constructor(
        handler: suspend (HttpRequest) -> HttpResponse,
        checkContract: Boolean = false,
    ) : this(NanoHttpServer("127.0.0.1", 0, Dispatchers.IO.limitedParallelism(4), handler).also { it.startServer() }, null, checkContract)

    /** Client für einen schon laufenden Server, etwa den eines [io.github.tycho991.openframe.core.FrameCore]. */
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

    /** Schickt den Rumpf als Strom bekannter Länge, ohne ihn im Speicher zu halten. */
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

    override fun close() {
        server?.stop()
    }
}
