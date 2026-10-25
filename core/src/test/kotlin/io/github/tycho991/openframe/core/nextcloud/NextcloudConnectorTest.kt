package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.library.PhotoIndexStore
import io.github.tycho991.openframe.core.library.StoredIndex
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.sources.Connection
import io.github.tycho991.openframe.core.sources.SourceConfigStore
import io.github.tycho991.openframe.core.sources.SourceException
import io.github.tycho991.openframe.core.sources.SourceRegistry
import io.github.tycho991.openframe.core.sources.StoredSources
import io.github.tycho991.openframe.core.sources.StoredSyncResults
import io.github.tycho991.openframe.core.sources.SyncResultStore
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
    private val connector by lazy {
        val registry =
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
        NextcloudConnector(registry, clients, clock)
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
