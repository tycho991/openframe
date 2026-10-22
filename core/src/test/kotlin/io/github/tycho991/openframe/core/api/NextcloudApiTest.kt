package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.nextcloud.FakeClients
import io.github.tycho991.openframe.core.nextcloud.FakeLoginFlow
import io.github.tycho991.openframe.core.nextcloud.NextcloudException
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.TestFrame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `nextcloud-source` through the API, every response checked against the contract. */
class NextcloudApiTest {
    @TempDir
    lateinit var dir: File

    private val clients = FakeClients()
    private val login = clients.login as FakeLoginFlow
    private val frame by lazy { TestFrame(dir, nextcloudClients = clients) }

    @AfterEach
    fun close() = frame.close()

    private fun state() = ApiJson.decodeFromString(NextcloudStateDto.serializer(), frame.get("/api/v1/nextcloud").body())

    private fun errorCode(body: String) =
        Json
            .parseToJsonElement(body)
            .jsonObject["error"]!!
            .jsonObject["code"]!!
            .jsonPrimitive.content

    /** Connects through the login flow the way the web UI does and returns the flow ID. */
    private fun connect(): String {
        val started = frame.send("POST", "/api/v1/nextcloud/login", """{"serverUrl":"https://cloud.example.org"}""")
        assertEquals(201, started.statusCode())
        val flow = ApiJson.decodeFromString(NextcloudLoginDto.serializer(), started.body())
        login.approved = true
        val done = ApiJson.decodeFromString(NextcloudLoginStateDto.serializer(), frame.get("/api/v1/nextcloud/login/${flow.flowId}").body())
        assertEquals("done", done.state)
        return flow.flowId
    }

    private fun waitFor(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(25)
        }
    }

    @Test
    fun `Not connected at the start, with the defaults`() {
        val s = state()
        assertFalse(s.connected)
        assertNull(s.serverUrl)
        assertNull(s.user)
        assertEquals(NextcloudSettingsDto(true, "/", true, true, 20, 2000), s.settings)
        assertFalse(s.status.syncing)
        assertNull(s.status.ok)
    }

    @Test
    fun `Every endpoint needs a token`() {
        for ((method, path) in listOf(
            "GET" to "/api/v1/nextcloud",
            "PATCH" to "/api/v1/nextcloud",
            "DELETE" to "/api/v1/nextcloud",
            "POST" to "/api/v1/nextcloud/login",
            "GET" to "/api/v1/nextcloud/login/abc",
            "POST" to "/api/v1/nextcloud/sync",
        )) {
            assertEquals(
                401,
                frame.http
                    .request(
                        method,
                        path,
                        if (method == "GET" ||
                            method == "DELETE"
                        ) {
                            null
                        } else {
                            "{}"
                        },
                    ).statusCode(),
                "$method $path",
            )
        }
    }

    @Test
    fun `The login is pending until the user approves, then the frame is connected`() {
        val started = frame.send("POST", "/api/v1/nextcloud/login", """{"serverUrl":"https://cloud.example.org/"}""")
        val flow = ApiJson.decodeFromString(NextcloudLoginDto.serializer(), started.body())
        assertEquals(listOf("https://cloud.example.org"), login.started, "the address is normalized")
        assertTrue(flow.loginUrl.endsWith("/index.php/login/v2/flow/abc"))
        val pending =
            ApiJson.decodeFromString(
                NextcloudLoginStateDto.serializer(),
                frame.get("/api/v1/nextcloud/login/${flow.flowId}").body(),
            )
        assertEquals("pending", pending.state)
        assertFalse(state().connected)
        login.approved = true
        val done = ApiJson.decodeFromString(NextcloudLoginStateDto.serializer(), frame.get("/api/v1/nextcloud/login/${flow.flowId}").body())
        assertEquals("done", done.state)
        assertEquals("Anna", done.user)
        val s = state()
        assertTrue(s.connected)
        assertEquals("Anna", s.user)
        assertEquals("https://cloud.example.org", s.serverUrl)
    }

    @Test
    fun `The password is in no response`() {
        connect()
        for (path in listOf("/api/v1/nextcloud", "/api/v1/logs")) {
            assertFalse(frame.get(path).body().contains(login.appPassword), path)
        }
    }

    @Test
    fun `An unknown or old flow is expired`() {
        val polled = frame.get("/api/v1/nextcloud/login/nope")
        assertEquals(200, polled.statusCode())
        assertEquals("expired", ApiJson.decodeFromString(NextcloudLoginStateDto.serializer(), polled.body()).state)
    }

    @Test
    fun `A wrong address is a 400 at the field and an unreachable server a 502`() {
        val bad = frame.send("POST", "/api/v1/nextcloud/login", """{"serverUrl":"ftp://cloud.example.org"}""")
        assertEquals(400, bad.statusCode())
        assertTrue("serverUrl" in bad.body())
        login.startFailure = NextcloudException(NextcloudException.Kind.UNREACHABLE, "The server cloud.example.org was not found")
        val down = frame.send("POST", "/api/v1/nextcloud/login", """{"serverUrl":"https://cloud.example.org"}""")
        assertEquals(502, down.statusCode())
        assertEquals("nextcloud_unreachable", errorCode(down.body()))
        assertFalse(state().connected, "nothing is stored")
    }

    @Test
    fun `Settings change partially and wrong values name the field`() {
        val ok = frame.send("PATCH", "/api/v1/nextcloud", """{"folder":"Photos/Family","intervalMinutes":45}""")
        assertEquals(200, ok.statusCode())
        val s = ApiJson.decodeFromString(NextcloudStateDto.serializer(), ok.body()).settings
        assertEquals("/Photos/Family", s.folder)
        assertEquals(45, s.intervalMinutes)
        assertTrue(s.removeVanished)
        val bad = frame.send("PATCH", "/api/v1/nextcloud", """{"intervalMinutes":2}""")
        assertEquals(400, bad.statusCode())
        assertTrue("intervalMinutes" in bad.body())
        assertEquals(45, state().settings.intervalMinutes)
    }

    @Test
    fun `A sync on request needs an account, runs and reports its result`() {
        assertEquals(409, frame.send("POST", "/api/v1/nextcloud/sync").statusCode())
        connect()
        clients.remote.put("holiday/a.jpg", Fixtures.file("landscape.jpg").readBytes(), "e1")
        val res = frame.send("POST", "/api/v1/nextcloud/sync")
        assertEquals(202, res.statusCode())
        waitFor("the sync") { state().status.ok == true && !state().status.syncing }
        val status = state().status
        assertEquals(1, status.added)
        assertNull(status.error)
        assertTrue(status.lastSyncAt != null)
        val photos = ApiJson.decodeFromString(PhotoPageDto.serializer(), frame.get("/api/v1/photos").body())
        assertEquals(listOf("holiday/a.jpg" to "nextcloud"), photos.items.map { it.path to it.source })
        assertEquals("holiday", photos.items.single().album)
        // A photo that the frame only reads cannot be deleted.
        assertEquals(409, frame.send("DELETE", "/api/v1/photos/${photos.items.single().id}").statusCode())
    }

    @Test
    fun `A failed sync shows a readable error and keeps the photos`() {
        connect()
        clients.remote.put("a.jpg", Fixtures.file("landscape.jpg").readBytes(), "e1")
        frame.send("POST", "/api/v1/nextcloud/sync")
        waitFor("the first sync") { state().status.ok == true && !state().status.syncing }
        clients.remote.listFailure =
            NextcloudException(NextcloudException.Kind.UNREACHABLE, "The server cloud.example.org did not answer in time")
        frame.send("POST", "/api/v1/nextcloud/sync")
        waitFor("the failure") { state().status.ok == false && !state().status.syncing }
        assertEquals("The server cloud.example.org did not answer in time", state().status.error)
        assertEquals(1, ApiJson.decodeFromString(PhotoPageDto.serializer(), frame.get("/api/v1/photos").body()).total)
    }

    @Test
    fun `Disconnecting removes the Nextcloud photos and leaves the local ones`() {
        connect()
        clients.remote.put("a.jpg", Fixtures.file("landscape.jpg").readBytes(), "e1")
        Fixtures.copy("image.png", File(frame.photos, "mine.png"))
        frame.scan()
        frame.send("POST", "/api/v1/nextcloud/sync")
        waitFor("the sync") { state().status.ok == true && !state().status.syncing }
        assertEquals(2, ApiJson.decodeFromString(PhotoPageDto.serializer(), frame.get("/api/v1/photos").body()).total)
        assertEquals(204, frame.send("DELETE", "/api/v1/nextcloud").statusCode())
        val s = state()
        assertFalse(s.connected)
        assertNull(s.status.ok)
        assertEquals(1, login.revoked)
        assertEquals(
            listOf("mine.png"),
            ApiJson.decodeFromString(PhotoPageDto.serializer(), frame.get("/api/v1/photos").body()).items.map {
                it.path
            },
        )
    }
}
