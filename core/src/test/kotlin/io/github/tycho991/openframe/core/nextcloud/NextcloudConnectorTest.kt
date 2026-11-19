@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.library.PhotoIndexStore
import io.github.tycho991.openframe.core.library.StoredIndex
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.sources.Connection
import io.github.tycho991.openframe.core.sources.SourceConfigStore
import io.github.tycho991.openframe.core.sources.SourceErrorCodes
import io.github.tycho991.openframe.core.sources.SourceException
import io.github.tycho991.openframe.core.sources.SourceRegistry
import io.github.tycho991.openframe.core.sources.StoredSource
import io.github.tycho991.openframe.core.sources.StoredSources
import io.github.tycho991.openframe.core.sources.StoredSyncResults
import io.github.tycho991.openframe.core.sources.SyncResultStore
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration

/** Spec `nextcloud-source`: Connect with the login flow (one flow at a time, 20 minutes, the same account again). */
class NextcloudConnectorTest {
    @TempDir
    lateinit var dir: File

    private val scope = TestScope()
    private val log = AppLog(TestClock())
    private val clock = TestClock()
    private val clients = FakeClients()
    private val login = clients.login as FakeLoginFlow

    private val configs by lazy {
        SourceConfigStore(JsonStore(File(dir, "sources.json"), StoredSources.serializer(), TestScope(), log)).also { it.load() }
    }
    private val registry by lazy {
        SourceRegistry(
            scope,
            configs,
            SyncResultStore(JsonStore(File(dir, "status.json"), StoredSyncResults.serializer(), TestScope(), log)),
            listOf(NextcloudAccountKind(clients), NextcloudShareKind(clients)),
            PhotoIndexStore(JsonStore(File(dir, "index.json"), StoredIndex.serializer(), TestScope(), log)).also { it.load() },
            JvmMediaEngine(),
            File(dir, "sources"),
            {},
            clock,
            log,
        ).also { it.start() }
    }
    private val connector by lazy { NextcloudConnector(registry, clients, clock) }

    /** Runs what the registry started in the background, such as a revocation. */
    private fun background() = scope.runCurrent()

    /** A sync of [id] on request, to its end: the scan itself runs on a thread of its own. */
    private fun sync(id: String) {
        assertTrue(registry.requestSync(id))
        val deadline = System.currentTimeMillis() + 10_000
        do {
            background()
            Thread.sleep(5)
            check(System.currentTimeMillis() < deadline) { "the sync did not end" }
        } while (registry.status(id).syncing)
        background()
    }

    /** Logs in at [typed] with the app password [password] and returns the source ID. */
    private fun login(
        typed: String,
        password: String,
        reported: String? = null,
    ): String {
        login.approved = false
        login.appPassword = password
        login.reportedServer = reported
        val flow = connector.startLogin(typed)
        login.approved = true
        val state = connector.pollLogin(flow.flowId)
        assertEquals("done", state.state, state.error)
        background()
        return state.sourceId!!
    }

    @AfterEach
    fun stop() = scope.cancel()

    @Test
    fun `A login not approved within 20 minutes has expired and stores nothing`() {
        val flow = connector.startLogin("https://cloud.example.org")
        assertEquals(clock.now() + Duration.ofMinutes(20), flow.expiresAt)
        clock.advance(Duration.ofMinutes(20).minusSeconds(1))
        assertEquals("pending", connector.pollLogin(flow.flowId).state)
        clock.advance(Duration.ofSeconds(1))
        login.approved = true
        assertEquals("expired", connector.pollLogin(flow.flowId).state)
        assertEquals(emptyList<Any>(), configs.sources.value)
    }

    @Test
    fun `A new login replaces the one before`() {
        val first = connector.startLogin("https://cloud.example.org")
        val second = connector.startLogin("https://cloud.example.org")
        login.approved = true
        assertEquals("expired", connector.pollLogin(first.flowId).state)
        assertEquals("done", connector.pollLogin(second.flowId).state)
        assertEquals(1, configs.sources.value.size)
    }

    @Test
    fun `The same server and user again get the new credentials, also after a move to https`() {
        val first = connector.startLogin("http://cloud.example.org")
        login.approved = true
        val id = connector.pollLogin(first.flowId).sourceId!!
        login.serverUrl = "https://cloud.example.org"
        val again = connector.startLogin("https://cloud.example.org")
        assertEquals(id, connector.pollLogin(again.flowId).sourceId)
        assertEquals(1, configs.sources.value.size)
        assertEquals("https://cloud.example.org", (configs.get(id)!!.connection as Connection.Account).serverUrl)
    }

    @Test
    fun `A new account at a full frame fails and its app password is revoked again`() {
        repeat(7) {
            configs.add(
                StoredSource(
                    configs.newId(),
                    NextcloudShareKind.ID,
                    "Share",
                    Connection.Share("https://cloud.example.org", "token$it"),
                    folder = "/",
                ),
            )
        }
        val first = connector.startLogin("https://cloud.example.org")
        login.approved = true
        connector.pollLogin(first.flowId)
        assertEquals(8, configs.sources.value.size)
        login.userId = "bert"
        val second = connector.startLogin("https://cloud.example.org")
        val state = connector.pollLogin(second.flowId)
        assertEquals("failed", state.state)
        assertEquals(SourceErrorCodes.TOO_MANY_SOURCES, state.code)
        assertEquals(0, login.revoked, "in the background, not in the request")
        background()
        assertEquals(1, login.revoked, "the unused app password does not stay active on the server")
        assertEquals(8, configs.sources.value.size)
    }

    // --- Spec nextcloud-source: No downgrade during the login, No orphaned app passwords

    @Test
    fun `Logging in again revokes the old app password, and only that one`() {
        val id = login("https://cloud.example.org", "pw-old-1111")
        assertEquals(emptyList<String>(), login.revokedPasswords)
        assertEquals(id, login("https://cloud.example.org", "pw-new-2222"))
        assertEquals(listOf("pw-old-1111"), login.revokedPasswords)
        assertEquals("pw-new-2222", (configs.get(id)!!.connection as Connection.Account).appPassword)
        // The same password again replaces nothing.
        assertEquals(id, login("https://cloud.example.org", "pw-new-2222"))
        assertEquals(listOf("pw-old-1111"), login.revokedPasswords)
    }

    @Test
    fun `An http account that moves to https on another port keeps its source and the old password is revoked`() {
        val id = login("http://cloud.example.org:8080", "pw-old-1111")
        val again = login("http://cloud.example.org:8080", "pw-new-2222", reported = "https://cloud.example.org:8443")
        assertEquals(id, again)
        assertEquals(1, configs.sources.value.size, "no second source")
        assertEquals("https://cloud.example.org:8443", (configs.get(id)!!.connection as Connection.Account).serverUrl)
        assertEquals(listOf("pw-old-1111"), login.revokedPasswords)
    }

    @Test
    fun `An https account logged in again with an http address runs the whole flow over https`() {
        val id = login("https://cloud.example.org", "pw-old-1111")
        login.started.clear()
        assertEquals(id, login("http://cloud.example.org", "pw-new-2222"))
        assertEquals(listOf("https://cloud.example.org"), login.started, "the flow starts on https, so its poll runs there")
        assertEquals("https://cloud.example.org", (configs.get(id)!!.connection as Connection.Account).serverUrl)
    }

    @Test
    fun `A login that fails after the approval revokes the new app password in the background`() {
        login.userIdFailure = SourceException(SourceException.Kind.SERVER, "The server answered with an error (HTTP 500)")
        login.appPassword = "pw-new-2222"
        val flow = connector.startLogin("https://cloud.example.org")
        login.approved = true
        val state = connector.pollLogin(flow.flowId)
        assertEquals("failed" to "server", state.state to state.code)
        background()
        assertEquals(listOf("pw-new-2222"), login.revokedPasswords)
        assertEquals(emptyList<Any>(), configs.sources.value)
    }

    @Test
    fun `A password the server refused is not sent again, neither at a new login nor at the removal`() {
        val id = login("https://cloud.example.org", "pw-old-1111")
        registry.patch(id, kotlinx.serialization.json.buildJsonObject { put("folder", kotlinx.serialization.json.JsonPrimitive("/")) })
        clients.remote.listFailure = SourceException(SourceException.Kind.UNAUTHORIZED, "The server refused the credentials (HTTP 401)")
        sync(id)
        assertTrue(registry.refusedCredentials(id), "the source is in the state of rejected access")
        login("https://cloud.example.org", "pw-new-2222")
        assertEquals(emptyList<String>(), login.revokedPasswords, "the refused password is not revoked")
        // Back in the refused state with the new password, the removal sends it no more either.
        sync(id)
        assertTrue(registry.refusedCredentials(id))
        registry.remove(id)
        background()
        assertEquals(emptyList<String>(), login.revokedPasswords)
    }

    @Test
    fun `Removing an account whose password works revokes it`() {
        val id = login("https://cloud.example.org", "pw-old-1111")
        registry.remove(id)
        background()
        assertEquals(listOf("pw-old-1111"), login.revokedPasswords)
    }

    @Test
    fun `A login that fails at the server is failed, over and stores nothing`() {
        login.userIdFailure = SourceException(SourceException.Kind.PROTOCOL, "The server did not name the user")
        val flow = connector.startLogin("https://cloud.example.org")
        login.approved = true
        val state = connector.pollLogin(flow.flowId)
        assertEquals("failed", state.state)
        assertEquals("The server did not name the user", state.error)
        assertEquals("expired", connector.pollLogin(flow.flowId).state, "a failed flow is over")
        assertEquals(emptyList<Any>(), configs.sources.value)
    }
}
