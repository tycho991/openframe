package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.library.PhotoIndexStore
import io.github.tycho991.openframe.core.library.StoredIndex
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.sources.Connection
import io.github.tycho991.openframe.core.sources.RemoteFile
import io.github.tycho991.openframe.core.sources.SourceConfigStore
import io.github.tycho991.openframe.core.sources.SourceException
import io.github.tycho991.openframe.core.sources.SourceRegistry
import io.github.tycho991.openframe.core.sources.StoredSource
import io.github.tycho991.openframe.core.sources.StoredSources
import io.github.tycho991.openframe.core.sources.StoredSyncResults
import io.github.tycho991.openframe.core.sources.SyncResultStore
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Spec `nextcloud-source`: No downgrade during the login, Login names beyond ASCII. The real login flow against the
 * fake server, over https with the certificate of the test authority where the rule is about https.
 */
class LoginSecurityTest {
    @TempDir
    lateinit var dir: File

    private val servers = ArrayList<FakeNextcloud>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterEach
    fun stop() {
        scope.cancel()
        servers.forEach { it.stop() }
    }

    private fun resource(name: String) = LoginSecurityTest::class.java.getResourceAsStream("/tls/$name")!!

    private val trusting by lazy {
        val root = resource("root.pem").use(TrustAnchors::parse)
        NextcloudHttp(NextcloudHttp.defaultClient(TrustAnchors.trustManager(listOf(root)), readTimeoutSeconds = 10))
    }

    /** A fake server on `https://localhost` with the test certificate, or on plain `http`. */
    private fun server(
        secure: Boolean,
        userId: String = "anna",
        loginName: String = "Anna",
    ): FakeNextcloud {
        val server = FakeNextcloud(userId = userId, loginName = loginName, host = if (secure) "localhost" else "127.0.0.1")
        if (secure) {
            val store = KeyStore.getInstance("PKCS12")
            resource("server.p12").use { store.load(it, "test".toCharArray()) }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "test".toCharArray()) }
            server.secure(SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }.serverSocketFactory)
        }
        server.start()
        servers += server
        return server
    }

    private fun startJson(
        endpoint: String,
        login: String,
    ) = """{"poll":{"token":"poll-token","endpoint":"$endpoint"},"login":"$login"}"""

    private fun refusal(
        server: FakeNextcloud,
        endpoint: String,
        login: String,
    ): SourceException {
        server.loginStartOverride = startJson(endpoint, login)
        return assertThrows(SourceException::class.java) { OkHttpLoginFlow(trusting).start(server.baseUrl) }
    }

    // --- The rule

    @Test
    fun `With an https server only https on the same host and port is accepted`() {
        val base = "https://cloud.example.org".toHttpUrl()
        assertTrue(LoginOrigin.accepts(base, "https://cloud.example.org/index.php/login/v2/poll".toHttpUrl()))
        assertTrue(LoginOrigin.accepts(base, "https://cloud.example.org:443/x".toHttpUrl()))
        assertEquals(LoginOrigin.Refusal.Downgrade, LoginOrigin.refusal(base, "http://cloud.example.org/x".toHttpUrl()))
        assertEquals(LoginOrigin.Refusal.Downgrade, LoginOrigin.refusal(base, "https://cloud.example.org:8443/x".toHttpUrl()))
        assertEquals(LoginOrigin.Refusal.Elsewhere, LoginOrigin.refusal(base, "https://evil.example.org/x".toHttpUrl()))
        assertEquals(LoginOrigin.Refusal.Elsewhere, LoginOrigin.refusal(base, null))
    }

    @Test
    fun `With an http server http and https on the same host are accepted, another host is not`() {
        val base = "http://192.168.100.215:8080".toHttpUrl()
        assertTrue(LoginOrigin.accepts(base, "http://192.168.100.215:8080/x".toHttpUrl()))
        assertTrue(LoginOrigin.accepts(base, "https://192.168.100.215:8443/x".toHttpUrl()))
        assertFalse(LoginOrigin.accepts(base, "http://192.168.100.216:8080/x".toHttpUrl()))
    }

    // --- Server reports an http endpoint, Login page on another host

    @Test
    fun `An https login whose server answers with http, another port, another host or javascript stops before the page opens`() {
        val server = server(secure = true)
        val good = server.baseUrl
        val http = good.replace("https://", "http://")
        val otherPort = "https://localhost:1"
        for ((endpoint, login) in listOf(
            "$http/index.php/login/v2/poll" to "$good/index.php/login/v2/flow/abc",
            "$otherPort/index.php/login/v2/poll" to "$good/index.php/login/v2/flow/abc",
            "$good/index.php/login/v2/poll" to "$http/index.php/login/v2/flow/abc",
        )) {
            val failure = refusal(server, endpoint, login)
            assertEquals(SourceException.Kind.INSECURE_LOGIN, failure.kind, "$endpoint $login")
            assertTrue(failure.message!!.contains("overwriteprotocol"), failure.message)
        }
        for (login in listOf("https://evil.example.org/index.php/login/v2/flow/abc", "javascript:alert(1)")) {
            val failure = refusal(server, "$good/index.php/login/v2/poll", login)
            assertEquals(SourceException.Kind.INSECURE_LOGIN, failure.kind, login)
            assertFalse(failure.message!!.contains("overwriteprotocol"), "only a downgrade gets the hint: ${failure.message}")
        }
        // Not a single request went to the poll address.
        assertTrue(server.requests.none { it.contains("poll") }, "${server.requests}")
    }

    /** Review finding 7: a redirect during the login follows the same rule as the addresses the server names. */
    @Test
    fun `A redirect of an https login to another port stops the login`() {
        val server = server(secure = true)
        server.redirects["/index.php/login/v2"] = "https://localhost:1/index.php/login/v2"
        val failure = assertThrows(SourceException::class.java) { OkHttpLoginFlow(trusting).start(server.baseUrl) }
        assertEquals(SourceException.Kind.INSECURE_LOGIN, failure.kind, failure.message)
        // A redirect of an http login to https on the same host is a move to https and is followed.
        val plain = server(secure = false)
        plain.redirects["/old"] = plain.baseUrl.replace("http://", "https://") + "/index.php/login/v2"
        assertEquals(null, LoginOrigin.refusal(plain.baseUrl.toHttpUrl(), plain.redirects.getValue("/old").toHttpUrl()))
    }

    @Test
    fun `An http login may move to https on the same host, an https one never moves to http`() {
        val plain = server(secure = false)
        plain.reportedServer = plain.baseUrl.replace("http://", "https://")
        plain.approved = true
        val flow = OkHttpLoginFlow(trusting)
        val done = flow.poll(flow.start(plain.baseUrl)) as LoginPoll.Done
        assertEquals(plain.baseUrl.replace("http://", "https://"), done.serverUrl, "http to https on the same host is taken")

        val secure = server(secure = true)
        secure.reportedServer = secure.baseUrl.replace("https://", "http://")
        secure.approved = true
        val again = flow.poll(flow.start(secure.baseUrl)) as LoginPoll.Done
        assertEquals(secure.baseUrl, again.serverUrl, "https to http is never taken")
    }

    @Test
    fun `A login answer over 64 KiB fails the login at the start and at the poll`() {
        val server = server(secure = false)
        val flow = OkHttpLoginFlow(trusting)
        server.loginStartOverride = """{"pad":"${"x".repeat(65 * 1024)}"}"""
        val start = assertThrows(SourceException::class.java) { flow.start(server.baseUrl) }
        assertEquals(SourceException.Kind.LIMIT, start.kind)
        assertTrue(start.message!!.contains("64 KiB"), start.message)
        server.loginStartOverride = null
        val started = flow.start(server.baseUrl)
        server.approved = true
        server.loginPollOverride =
            """{"server":"${server.baseUrl}","loginName":"Anna","appPassword":"pw","pad":"${"x".repeat(65 * 1024)}"}"""
        assertEquals(SourceException.Kind.LIMIT, assertThrows(SourceException::class.java) { flow.poll(started) }.kind)
    }

    // --- https account and an http address

    private fun registry(clients: NextcloudClients): SourceRegistry {
        val log = AppLog(TestClock())
        return SourceRegistry(
            scope,
            SourceConfigStore(JsonStore(File(dir, "sources.json"), StoredSources.serializer(), TestScope(), log)).also { it.load() },
            SyncResultStore(JsonStore(File(dir, "status.json"), StoredSyncResults.serializer(), TestScope(), log)),
            listOf(NextcloudAccountKind(clients)),
            PhotoIndexStore(JsonStore(File(dir, "index.json"), StoredIndex.serializer(), TestScope(), log)).also { it.load() },
            JvmMediaEngine(),
            File(dir, "sources"),
            TestClock(),
            log,
        ).also { it.start() }
    }

    @Test
    fun `A login again typed with http for an https account runs over https and stays there`() {
        val server = server(secure = true)
        val clients = OkHttpNextcloudClients { trusting }
        val registry = registry(clients)
        val connector = NextcloudConnector(registry, clients, TestClock())
        registry.add(
            StoredSource(
                "s1",
                NextcloudAccountKind.ID,
                "localhost",
                Connection.Account(server.baseUrl, server.userId, server.loginName, "old-password"),
            ),
        )
        server.approved = true
        val flow = connector.startLogin(server.baseUrl.replace("https://", "http://"))
        assertTrue(flow.loginUrl.startsWith("https://"), flow.loginUrl)
        val state = connector.pollLogin(flow.flowId)
        assertEquals("done", state.state, state.error)
        assertEquals("s1", state.sourceId)
        assertEquals(
            server.baseUrl,
            registry.configs
                .get("s1")!!
                .connection.serverUrl,
        )
        // The server speaks only TLS: every request it took came over https, the poll with the app password included.
        assertTrue(server.requests.contains("POST /index.php/login/v2"), "${server.requests}")
        assertTrue(server.requests.contains("POST /index.php/login/v2/poll"), "${server.requests}")
    }

    // --- Login names beyond ASCII

    @Test
    fun `Login, sync and revocation work for the login name jürgen`() {
        val server = server(secure = false, userId = "jürgen", loginName = "jürgen")
        server.put("Fotos/a.jpg", ByteArray(10) { it.toByte() }, "e1")
        server.approved = true
        val flow = OkHttpLoginFlow(trusting)
        val done = flow.poll(flow.start(server.baseUrl)) as LoginPoll.Done
        assertEquals("jürgen", done.loginName)
        assertEquals("jürgen", flow.userId(done.serverUrl, done.loginName, done.appPassword))
        val folder = WebDavFolder(trusting, done.serverUrl, "jürgen", done.loginName, done.appPassword, "/Fotos")
        val file: RemoteFile = folder.list(true).single()
        val target = File(dir, "a.part")
        folder.download(file, target)
        assertArrayEquals(ByteArray(10) { it.toByte() }, target.readBytes())
        flow.revoke(done.serverUrl, done.loginName, done.appPassword)
        assertTrue(server.revoked)
    }
}
