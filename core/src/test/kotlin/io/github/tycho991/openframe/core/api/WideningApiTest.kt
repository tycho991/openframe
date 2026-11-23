package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.nextcloud.FakeClients
import io.github.tycho991.openframe.core.nextcloud.FakeLoginFlow
import io.github.tycho991.openframe.core.testing.Res
import io.github.tycho991.openframe.core.testing.TestClock
import io.github.tycho991.openframe.core.testing.TestFrame
import io.github.tycho991.openframe.core.testing.waitFor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration

/** Spec `remote-sources`, "Proof for widening an account folder", through the API with the contract check on. */
class WideningApiTest {
    @TempDir
    lateinit var dir: File

    private val clock = TestClock()
    private val clients = FakeClients()
    private val login = clients.login as FakeLoginFlow
    private val frame by lazy { TestFrame(dir, nextcloudClients = clients, clock = clock) }

    /** The laptop is the frame's own test client; the phone pairs later, a visitor's device. */
    private val laptop get() = frame.auth
    private val phone by lazy { mapOf("Authorization" to "Bearer ${frame.pair("Phone")}") }

    @AfterEach
    fun close() = frame.close()

    private fun error(res: Res) = Json.parseToJsonElement(res.body()).jsonObject["error"]!!.jsonObject

    private fun folder(id: String) =
        ApiJson.decodeFromString(SourceDto.serializer(), frame.get("/api/v1/sources/$id").body()).settings.folder

    private fun patch(
        id: String,
        body: String,
        auth: Map<String, String>,
    ) = frame.http.request("PATCH", "/api/v1/sources/$id", body, auth)

    /** A login of [auth] to completion; with [confirm] a confirmation login for that source. Returns the final state. */
    private fun login(
        auth: Map<String, String>,
        confirm: String? = null,
        user: String = login.userId,
    ): SourceLoginStateDto {
        val body = if (confirm != null) """{"confirmSourceId":"$confirm"}""" else """{"serverUrl":"https://cloud.example.org"}"""
        val started = frame.http.request("POST", "/api/v1/sources/login", body, auth)
        assertEquals(201, started.statusCode(), started.body())
        val flow = ApiJson.decodeFromString(SourceLoginDto.serializer(), started.body())
        login.approve(user)
        return ApiJson.decodeFromString(
            SourceLoginStateDto.serializer(),
            frame.http.get("/api/v1/sources/login/${flow.flowId}", auth).body(),
        )
    }

    /** The laptop connects the account and chooses `/Photos` as its first folder, without a second login. */
    private fun connected(): String {
        val done = login(laptop)
        assertEquals("done", done.state, done.error)
        val id = done.sourceId!!
        val first = patch(id, """{"folder":"/Photos","includeSubfolders":false}""", laptop)
        assertEquals(200, first.statusCode(), first.body())
        return id
    }

    /** › Visitor widens to the whole account; › Proof of another device; › First folder after connecting. */
    @Test
    fun `A device without a proof cannot widen and nothing syncs the whole account`() {
        val id = connected()
        val res = patch(id, """{"folder":"/"}""", phone)
        assertEquals(403, res.statusCode(), res.body())
        assertEquals("account_login_required", error(res)["code"]!!.jsonPrimitive.content)
        assertEquals(setOf("folder"), error(res)["fields"]!!.jsonObject.keys)
        assertEquals("/Photos", folder(id))
        val both = patch(id, """{"folder":"/Photos/2024","includeSubfolders":true}""", phone)
        assertEquals(setOf("folder", "includeSubfolders"), error(both)["fields"]!!.jsonObject.keys)
        Thread.sleep(300)
        assertFalse("/" in clients.accountFolders, "no sync of the whole account")
    }

    /** › After a confirmation login. */
    @Test
    fun `After a confirmation login the device widens to the whole account`() {
        val id = connected()
        val confirmed = login(phone, confirm = id)
        assertEquals("done", confirmed.state, confirmed.error)
        assertEquals(id, confirmed.sourceId)
        clock.advance(Duration.ofMinutes(10))
        val res = patch(id, """{"folder":"/","includeSubfolders":true}""", phone)
        assertEquals(200, res.statusCode(), res.body())
        assertEquals("/", folder(id))
        waitFor(message = "a sync of the whole account") { "/" in clients.accountFolders }
    }

    /** › Proof expired. */
    @Test
    fun `A proof 31 minutes old has expired`() {
        val id = connected()
        clock.advance(Duration.ofMinutes(31))
        val res = patch(id, """{"folder":"/"}""", laptop)
        assertEquals(403, res.statusCode(), res.body())
        assertEquals("account_login_required", error(res)["code"]!!.jsonPrimitive.content)
    }

    /** › Invalid value and widening together. */
    @Test
    fun `An invalid value comes before the widening`() {
        val id = connected()
        val res = patch(id, """{"folder":"/","intervalMinutes":1}""", phone)
        assertEquals(400, res.statusCode(), res.body())
        assertEquals(setOf("intervalMinutes"), error(res)["fields"]!!.jsonObject.keys)
        assertEquals("/Photos", folder(id))
    }

    /** › Narrowing and other settings. */
    @Test
    fun `Narrowing, renaming and the interval need no proof`() {
        val id = connected()
        assertEquals(200, patch(id, """{"includeSubfolders":false,"name":"Family","intervalMinutes":45}""", phone).statusCode())
        // From the whole account down, with subfolders on: narrowing as well.
        val confirmed = login(laptop, confirm = id)
        assertEquals("done", confirmed.state)
        assertEquals(200, patch(id, """{"folder":"/","includeSubfolders":true}""", laptop).statusCode())
        val narrowed = patch(id, """{"folder":"/Photos/2024"}""", phone)
        assertEquals(200, narrowed.statusCode(), narrowed.body())
        assertEquals("/Photos/2024", folder(id))
    }

    /** A share link's token bounds what it reads: its folder changes freely (› Share link). */
    @Test
    fun `A share link widens without a proof`() {
        clients.share("AbC123")
        val added =
            frame.http.request(
                "POST",
                "/api/v1/sources",
                """{"kind":"nextcloud-share","link":"https://cloud.example.org/s/AbC123"}""",
                laptop,
            )
        assertEquals(201, added.statusCode(), added.body())
        val id = ApiJson.decodeFromString(SourceDto.serializer(), added.body()).id
        assertEquals(200, patch(id, """{"folder":"/Sub"}""", phone).statusCode())
        assertEquals(200, patch(id, """{"folder":"/"}""", phone).statusCode())
    }

    /** A revoked device's proof counts for nothing, and a removed source's proofs go with it. */
    @Test
    fun `A proof ends with the pairing and with the source`() {
        val id = connected()
        val phoneAuth = phone
        val phoneId =
            frame.core.clients.clients.value
                .first { it.name == "Phone" }
                .id
        assertEquals("done", login(phoneAuth, confirm = id).state)
        assertEquals(
            true,
            frame.core.sources.grants
                .has(phoneId, id),
        )
        frame.core.clients.remove(phoneId)
        assertEquals(
            false,
            frame.core.sources.grants
                .has(phoneId, id),
        )
        val laptopId =
            frame.core.clients.clients.value
                .first { it.name == "Test" }
                .id
        assertEquals(
            true,
            frame.core.sources.grants
                .has(laptopId, id),
        )
        assertEquals(204, frame.send("DELETE", "/api/v1/sources/$id").statusCode())
        assertEquals(
            false,
            frame.core.sources.grants
                .has(laptopId, id),
        )
    }
}
