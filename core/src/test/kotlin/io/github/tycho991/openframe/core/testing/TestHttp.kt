package io.github.tycho991.openframe.core.testing

import io.github.tycho991.openframe.core.http.HttpRequest
import io.github.tycho991.openframe.core.http.HttpResponse
import io.github.tycho991.openframe.core.http.NanoHttpServer
import kotlinx.coroutines.Dispatchers
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest as JdkRequest
import java.net.http.HttpResponse as JdkResponse

/** Ein echter Server auf einem freien Port und ein Client dazu. */
class TestHttp(
    handler: suspend (HttpRequest) -> HttpResponse,
    /** Prüft jede Antwort unter `/api/` gegen den API-Vertrag. */
    private val checkContract: Boolean = false,
) : AutoCloseable {
    private val server = NanoHttpServer("127.0.0.1", 0, Dispatchers.IO.limitedParallelism(4), handler).also { it.startServer() }
    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    val base: String get() = "http://127.0.0.1:${server.port}"

    fun request(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        contentType: String? = if (body != null) "application/json" else null,
    ): JdkResponse<String> =
        requestBytes(method, path, body?.toByteArray(), headers, contentType).let { Text(it) }.also { res ->
            if (checkContract && path.startsWith("/api/")) {
                Contract.check(method, path, res.statusCode(), res.headers().firstValue("content-type").orElse(null), res.body())
            }
        }

    fun requestBytes(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        contentType: String? = null,
    ): JdkResponse<ByteArray> {
        val builder = JdkRequest.newBuilder(URI.create(base + path))
        val publisher = if (body != null) JdkRequest.BodyPublishers.ofByteArray(body) else JdkRequest.BodyPublishers.noBody()
        builder.method(method, publisher)
        contentType?.let { builder.header("Content-Type", it) }
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.send(builder.build(), JdkResponse.BodyHandlers.ofByteArray())
    }

    fun get(
        path: String,
        headers: Map<String, String> = emptyMap(),
    ) = request("GET", path, headers = headers)

    override fun close() {
        server.stop()
    }

    /** Antwort mit Text-Rumpf aus einer Byte-Antwort. */
    private class Text(
        private val raw: JdkResponse<ByteArray>,
    ) : JdkResponse<String> by DelegatingResponse(raw, raw.body().toString(Charsets.UTF_8))

    private class DelegatingResponse(
        private val raw: JdkResponse<ByteArray>,
        private val text: String,
    ) : JdkResponse<String> {
        override fun statusCode() = raw.statusCode()

        override fun request() = raw.request()

        override fun previousResponse() = java.util.Optional.empty<JdkResponse<String>>()

        override fun headers() = raw.headers()

        override fun body() = text

        override fun sslSession() = raw.sslSession()

        override fun uri() = raw.uri()

        override fun version() = raw.version()
    }
}
