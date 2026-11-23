@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.ValidationException
import io.github.tycho991.openframe.core.http.HttpException
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
import org.junit.jupiter.api.Assertions.assertThrows
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
        val flow = connector.startLogin(typed, LAPTOP)
        login.approved = true
        val state = connector.pollLogin(flow.flowId, LAPTOP)
        assertEquals("done", state.state, state.error)
        background()
        return state.sourceId!!
    }

    @AfterEach
    fun stop() = scope.cancel()

    @Test
    fun `A login not approved within 20 minutes has expired and stores nothing`() {
        val flow = connector.startLogin("https://cloud.example.org", LAPTOP)
        assertEquals(clock.now() + Duration.ofMinutes(20), flow.expiresAt)
        clock.advance(Duration.ofMinutes(20).minusSeconds(1))
        assertEquals("pending", connector.pollLogin(flow.flowId, LAPTOP).state)
        clock.advance(Duration.ofSeconds(1))
        login.approved = true
        assertEquals("expired", connector.pollLogin(flow.flowId, LAPTOP).state)
        assertEquals(emptyList<Any>(), configs.sources.value)
    }

    @Test
    fun `A new login replaces the one before`() {
        val first = connector.startLogin("https://cloud.example.org", LAPTOP)
        val second = connector.startLogin("https://cloud.example.org", LAPTOP)
        login.approved = true
        assertEquals("expired", connector.pollLogin(first.flowId, LAPTOP).state)
        assertEquals("done", connector.pollLogin(second.flowId, LAPTOP).state)
        assertEquals(1, configs.sources.value.size)
    }

    @Test
    fun `The same server and user again get the new credentials, also after a move to https`() {
        val first = connector.startLogin("http://cloud.example.org", LAPTOP)
        login.approved = true
        val id = connector.pollLogin(first.flowId, LAPTOP).sourceId!!
        login.serverUrl = "https://cloud.example.org"
        val again = connector.startLogin("https://cloud.example.org", LAPTOP)
        assertEquals(id, connector.pollLogin(again.flowId, LAPTOP).sourceId)
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
        val first = connector.startLogin("https://cloud.example.org", LAPTOP)
        login.approved = true
        connector.pollLogin(first.flowId, LAPTOP)
        assertEquals(8, configs.sources.value.size)
        login.userId = "bert"
        val second = connector.startLogin("https://cloud.example.org", LAPTOP)
        val state = connector.pollLogin(second.flowId, LAPTOP)
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
        val flow = connector.startLogin("https://cloud.example.org", LAPTOP)
        login.approved = true
        val state = connector.pollLogin(flow.flowId, LAPTOP)
        assertEquals("failed" to "server", state.state to state.code)
        background()
        assertEquals(listOf("pw-new-2222"), login.revokedPasswords)
        assertEquals(emptyList<Any>(), configs.sources.value)
    }

    @Test
    fun `A password the server refused is not sent again, neither at a new login nor at the removal`() {
        val id = login("https://cloud.example.org", "pw-old-1111")
        registry.patch(
            id,
            kotlinx.serialization.json.buildJsonObject { put("folder", kotlinx.serialization.json.JsonPrimitive("/")) },
            LAPTOP,
        )
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

    /** Review finding 4 of `nextcloud-hardening`: a refusal of old credentials does not protect the new ones from a revoke. */
    @Test
    fun `Credentials that replaced refused ones are revoked when they are replaced in turn or the source goes`() {
        val id = login("https://cloud.example.org", "pw-0000")
        registry.patch(
            id,
            kotlinx.serialization.json.buildJsonObject { put("folder", kotlinx.serialization.json.JsonPrimitive("/")) },
            LAPTOP,
        )
        clients.remote.listFailure = SourceException(SourceException.Kind.UNAUTHORIZED, "The server refused the credentials (HTTP 401)")
        sync(id)
        assertTrue(registry.refusedCredentials(id))
        // A new login before any sync with it: the refusal was of the old password.
        login("https://cloud.example.org", "pw-1111")
        assertEquals(emptyList<String>(), login.revokedPasswords, "the refused password is not sent again")
        assertTrue(!registry.refusedCredentials(id))
        login("https://cloud.example.org", "pw-2222")
        assertEquals(listOf("pw-1111"), login.revokedPasswords, "the password that was never refused is revoked")
        registry.remove(id)
        background()
        assertEquals(listOf("pw-1111", "pw-2222"), login.revokedPasswords)
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
        val flow = connector.startLogin("https://cloud.example.org", LAPTOP)
        login.approved = true
        val state = connector.pollLogin(flow.flowId, LAPTOP)
        assertEquals("failed", state.state)
        assertEquals("The server did not name the user", state.error)
        assertEquals("expired", connector.pollLogin(flow.flowId, LAPTOP).state, "a failed flow is over")
        assertEquals(emptyList<Any>(), configs.sources.value)
    }

    // --- Spec nextcloud-source: Login flows per device (design decision 15 of `paired-device-guards`)

    @Test
    fun `Two devices log in at once and each completes its own login`() {
        val laptopFlow = connector.startLogin("https://cloud.example.org", LAPTOP)
        val laptopStart = login.flows.last()
        val phoneFlow = connector.startLogin("https://cloud.example.org", PHONE)
        assertEquals("pending", connector.pollLogin(laptopFlow.flowId, LAPTOP).state, "the phone's start replaced nothing")
        login.approve(flow = laptopStart)
        assertEquals("pending", connector.pollLogin(phoneFlow.flowId, PHONE).state)
        val laptop = connector.pollLogin(laptopFlow.flowId, LAPTOP)
        assertEquals("done", laptop.state)
        login.approve(user = "bert")
        val phone = connector.pollLogin(phoneFlow.flowId, PHONE)
        assertEquals("done", phone.state)
        assertTrue(laptop.sourceId != phone.sourceId, "two accounts, two sources")
    }

    @Test
    fun `A poll of another device's flow is expired and the starting device still completes it`() {
        val flow = connector.startLogin("https://cloud.example.org", LAPTOP)
        login.approve()
        assertEquals("expired", connector.pollLogin(flow.flowId, PHONE).state)
        assertEquals(emptyList<Any>(), configs.sources.value, "the other device neither read nor completed it")
        assertEquals("done", connector.pollLogin(flow.flowId, LAPTOP).state)
    }

    @Test
    fun `The flow of a device whose pairing is revoked ends`() {
        val paired = HashSet(listOf(LAPTOP, PHONE))
        val guarded = NextcloudConnector(registry, clients, clock, { it in paired })
        val flow = guarded.startLogin("https://cloud.example.org", LAPTOP)
        login.approve()
        paired -= LAPTOP
        guarded.startLogin("https://cloud.example.org", PHONE)
        paired += LAPTOP
        assertEquals("expired", guarded.pollLogin(flow.flowId, LAPTOP).state, "swept at the phone's start")
        assertEquals(emptyList<Any>(), configs.sources.value)
    }

    // --- Spec nextcloud-source: Confirm an account by login (design decision 16 of `paired-device-guards`)

    private fun confirm(
        sourceId: String,
        user: String,
        client: String = PHONE,
    ): LoginState {
        val flow = connector.startLogin(null, client, confirmSourceId = sourceId)
        login.approve(user)
        return connector.pollLogin(flow.flowId, client)
    }

    @Test
    fun `The same account confirmed revokes the new password and changes nothing of the source`() {
        val id = login("https://cloud.example.org", "pw-old-1111")
        val before = configs.get(id)
        val result = registry.status(id)
        val started = login.started.size
        val state = confirm(id, "anna")
        assertEquals("done", state.state, state.error)
        assertEquals(id, state.sourceId)
        assertEquals("https://cloud.example.org", login.started.drop(started).single(), "on the source's server")
        background()
        assertEquals(1, login.revoked, "the new app password is revoked")
        assertTrue(login.revokedPasswords.single().startsWith("pw-old-1111-token"), login.revokedPasswords.toString())
        assertEquals(before, configs.get(id), "the old password stays")
        assertEquals(result, registry.status(id), "status and result unchanged, no sync requested")
        assertTrue(registry.grants.has(PHONE, id))
        assertTrue(!registry.grants.has("fedcba9876543210", id))
    }

    @Test
    fun `Another account fails with other_account, is revoked and adds no source`() {
        val id = login("https://cloud.example.org", "pw-old-1111")
        val state = confirm(id, "mallory")
        assertEquals("failed", state.state)
        assertEquals(SourceErrorCodes.OTHER_ACCOUNT, state.code)
        background()
        assertEquals(1, login.revoked)
        assertEquals(1, configs.sources.value.size)
        assertTrue(!registry.grants.has(PHONE, id))
    }

    @Test
    fun `The account of another source fails as well and neither source reconnects`() {
        val first = login("https://cloud.example.org", "pw-anna-1111")
        login.userId = "bert"
        val second = login("https://cloud.example.org", "pw-bert-2222")
        login.userId = "anna"
        val before = configs.sources.value
        val state = confirm(first, "bert")
        assertEquals("failed", state.state)
        assertEquals(SourceErrorCodes.OTHER_ACCOUNT, state.code)
        background()
        assertEquals(before, configs.sources.value, "neither source changed its credentials")
        assertEquals(1, login.revoked)
        assertTrue(!registry.grants.has(PHONE, first) && !registry.grants.has(PHONE, second))
    }

    @Test
    fun `A share link and an unknown source start no confirmation`() {
        val share =
            configs.add(
                StoredSource(
                    configs.newId(),
                    NextcloudShareKind.ID,
                    "Share",
                    Connection.Share("https://cloud.example.org", "tok"),
                    folder = "/",
                ),
            )
        val flows = login.flows.size
        val shareError = assertThrows(ValidationException::class.java) { connector.startLogin(null, PHONE, confirmSourceId = share.id) }
        assertEquals(setOf("confirmSourceId"), shareError.fields.keys)
        assertEquals(
            404,
            assertThrows(HttpException::class.java) { connector.startLogin(null, PHONE, confirmSourceId = "s99999999") }.status,
        )
        assertEquals(flows, login.flows.size, "no flow started")
    }

    @Test
    fun `A confirmation on a full frame starts`() {
        val id = login("https://cloud.example.org", "pw-old-1111")
        repeat(7) {
            configs.add(
                StoredSource(
                    configs.newId(),
                    NextcloudShareKind.ID,
                    "Share",
                    Connection.Share("https://cloud.example.org", "t$it"),
                    folder = "/",
                ),
            )
        }
        assertEquals(8, configs.sources.value.size)
        assertEquals("done", confirm(id, "anna").state)
    }
}

/** The client that logs in, as the route passes it. */
private const val LAPTOP = "0123456789abcdef"

/** Another paired client. */
private const val PHONE = "aaaaaaaaaaaaaaaa"
