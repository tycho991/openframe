package io.github.tycho991.openframe.core.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import io.github.tycho991.openframe.core.nextcloud.TrustAnchors
import io.github.tycho991.openframe.core.testing.FakeReleaseSource
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * GitHub's API and asset host over TLS on the loopback address (design decision 16 of `app-auto-update`), with the test
 * certificates under `resources/tls`; [root] is the root to trust. A plain `http` server next to it records whether
 * anything was ever sent without TLS.
 */
class FakeGithub(
    keystore: String = "server.p12",
) : AutoCloseable {
    /** A request as the server saw it. */
    class Seen(
        val path: String,
        val headers: Map<String, String>,
    )

    /** One answer: status, body and headers. */
    class Answer(
        val status: Int,
        val body: ByteArray = ByteArray(0),
        val headers: Map<String, String> = emptyMap(),
    )

    val requests = CopyOnWriteArrayList<Seen>()

    /** Requests that reached the plain `http` server; must stay empty. */
    val plainRequests = CopyOnWriteArrayList<String>()

    /** The answer for the latest release; by default from [releaseJson], with an ETag and `304` for it. */
    @Volatile var latest: (Seen) -> Answer = { seen -> releaseAnswer(seen) }

    @Volatile var releaseJson: String = "{}"

    /** The files of the asset host by path, and redirects by path. */
    val assets = mutableMapOf<String, ByteArray>()
    val redirects = mutableMapOf<String, String>()

    private val https: HttpsServer
    private val plain: HttpServer

    val base: String
    val plainBase: String

    init {
        val store = KeyStore.getInstance("PKCS12")
        resource(keystore).use { store.load(it, "test".toCharArray()) }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "test".toCharArray()) }
        val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        https = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        https.httpsConfigurator = HttpsConfigurator(context)
        https.createContext("/") { exchange -> serve(exchange) }
        https.start()
        plain = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        plain.createContext("/") { exchange ->
            plainRequests += exchange.requestURI.path
            send(exchange, Answer(200, "plain".toByteArray()))
        }
        plain.start()
        base = "https://localhost:${https.address.port}"
        plainBase = "http://localhost:${plain.address.port}"
    }

    /** The client the frame would use, trusting [root] in addition to the platform. */
    fun client(roots: List<X509Certificate> = listOf(root)): GithubReleases =
        GithubReleases(base, "tycho991/openframe", TrustAnchors.trustManager(roots))

    /** Publishes [tag] with [assets] as GitHub describes a release; each asset as `name to bytes`, with its digest. */
    fun publish(
        tag: String,
        vararg files: Pair<String, ByteArray>,
        digests: Boolean = true,
        draft: Boolean = false,
        prerelease: Boolean = false,
        notes: String = "Notes of $tag",
    ) {
        val list =
            files.joinToString(",") { (name, bytes) ->
                assets["/assets/$name"] = bytes
                val digest = if (digests) "\"sha256:${FakeReleaseSource.sha256(bytes)}\"" else "null"
                """{"name":"$name","size":${bytes.size},"digest":$digest,"browser_download_url":"$base/assets/$name","url":"$base/x"}"""
            }
        releaseJson =
            """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"body":"$notes","published_at":"2026-11-01T10:00:00Z",""" +
            """"assets":[$list],"author":{"login":"someone"}}"""
    }

    private fun releaseAnswer(seen: Seen): Answer {
        val etag = "\"" + FakeReleaseSource.sha256(releaseJson.toByteArray()).take(16) + "\""
        if (seen.headers["if-none-match"] == etag) return Answer(304, headers = mapOf("ETag" to etag))
        return Answer(200, releaseJson.toByteArray(), mapOf("ETag" to etag, "Content-Type" to "application/json"))
    }

    private fun serve(exchange: HttpExchange) {
        val seen = Seen(exchange.requestURI.path, exchange.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.first() })
        requests += seen
        val answer =
            when {
                seen.path == "/repos/tycho991/openframe/releases/latest" -> latest(seen)
                seen.path in redirects -> Answer(302, headers = mapOf("Location" to redirects.getValue(seen.path)))
                seen.path in assets -> Answer(200, assets.getValue(seen.path))
                else -> Answer(404)
            }
        send(exchange, answer)
    }

    private fun send(
        exchange: HttpExchange,
        answer: Answer,
    ) {
        answer.headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
        val length = if (answer.body.isEmpty()) -1L else answer.body.size.toLong()
        exchange.sendResponseHeaders(answer.status, length)
        exchange.responseBody.use { if (answer.body.isNotEmpty()) it.write(answer.body) }
    }

    override fun close() {
        https.stop(0)
        plain.stop(0)
    }

    companion object {
        private fun resource(name: String) = FakeGithub::class.java.getResourceAsStream("/tls/$name")!!

        /** The root of `server.p12`. */
        val root: X509Certificate get() = resource("root.pem").use(TrustAnchors::parse)
    }
}
