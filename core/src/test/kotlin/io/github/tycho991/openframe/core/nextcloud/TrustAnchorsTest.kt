package io.github.tycho991.openframe.core.nextcloud

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** Spec `nextcloud-source`: Trusted connections on old devices. */
class TrustAnchorsTest {
    private var server: HttpsServer? = null

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    private fun resource(name: String) = TrustAnchorsTest::class.java.getResourceAsStream("/tls/$name")!!

    private fun testRoot(name: String): X509Certificate = resource(name).use(TrustAnchors::parse)

    /** An HTTPS server on the loopback address whose certificate is in [keystore]. */
    private fun start(keystore: String): String {
        val store = KeyStore.getInstance("PKCS12")
        resource(keystore).use { store.load(it, "test".toCharArray()) }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "test".toCharArray()) }
        val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        val https = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        https.httpsConfigurator = HttpsConfigurator(context)
        https.createContext("/") { exchange ->
            val body = "hello".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        https.start()
        server = https
        return "https://localhost:${https.address.port}/"
    }

    private fun get(
        url: String,
        extraRoots: List<X509Certificate>,
    ): String {
        val http = NextcloudHttp(NextcloudHttp.defaultClient(TrustAnchors.trustManager(extraRoots)))
        return http.execute(Request.Builder().url(url).build()).use { it.body!!.string() }
    }

    @Test
    fun `The delivered roots parse and are valid`() {
        val roots = TrustAnchors.delivered()
        assertEquals(3, roots.size)
        for (root in roots) {
            root.checkValidity()
            assertTrue(root.basicConstraints >= 0, "${root.subjectX500Principal} is a certificate authority")
        }
        assertTrue(roots.any { "ISRG Root X1" in it.subjectX500Principal.name })
    }

    @Test
    fun `A chain under a delivered root is accepted`() {
        val url = start("server.p12")
        assertEquals("hello", get(url, listOf(testRoot("root.pem"))))
    }

    @Test
    fun `A chain under an unknown authority is refused`() {
        val url = start("server.p12")
        val failure = assertThrows(NextcloudException::class.java) { get(url, TrustAnchors.delivered()) }
        assertEquals(NextcloudException.Kind.TLS, failure.kind)
    }

    @Test
    fun `A delivered root does not vouch for another authority`() {
        val url = start("other.p12")
        val failure = assertThrows(NextcloudException::class.java) { get(url, listOf(testRoot("root.pem"))) }
        assertEquals(NextcloudException.Kind.TLS, failure.kind)
    }
}
