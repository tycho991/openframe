package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.nextcloud.FakeClients
import io.github.tycho991.openframe.core.nextcloud.FakeLoginFlow
import io.github.tycho991.openframe.core.sources.SourceException
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

/**
 * Specs `remote-sources` and `nextcloud-source` through the API, every response checked against the contract: the list,
 * adding a share with each of its outcomes, the login flow for an account, settings, sync and removal per source.
 */
class SourcesApiTest {
    @TempDir
    lateinit var dir: File

    private val clients = FakeClients()
    private val login = clients.login as FakeLoginFlow
    private val frame by lazy { TestFrame(dir, nextcloudClients = clients) }

    @AfterEach
    fun close() = frame.close()

    private fun list() = ApiJson.decodeFromString(SourceListDto.serializer(), frame.get("/api/v1/sources").body()).sources

    private fun source(id: String) = ApiJson.decodeFromString(SourceDto.serializer(), frame.get("/api/v1/sources/$id").body())

    private fun photos() = ApiJson.decodeFromString(PhotoPageDto.serializer(), frame.get("/api/v1/photos").body())

    private fun errorCode(body: String) =
        Json
            .parseToJsonElement(body)
            .jsonObject["error"]!!
            .jsonObject["code"]!!
            .jsonPrimitive.content

    private fun fields(body: String) =
        Json
            .parseToJsonElement(body)
            .jsonObject["error"]!!
            .jsonObject["fields"]
            ?.jsonObject
            ?.keys
            .orEmpty()

    private fun addShare(
        link: String,
        password: String? = null,
        name: String? = null,
    ) = frame.send(
        "POST",
        "/api/v1/sources",
        buildString {
            append("""{"kind":"nextcloud-share","link":"$link"""")
            if (password != null) append(""","password":"$password"""")
            if (name != null) append(""","name":"$name"""")
            append("}")
        },
    )

    /** Adds the share `token` on the fake server through the API and returns the new source. */
    private fun shared(
        token: String,
        name: String? = null,
    ): SourceDto {
        clients.share(token)
        val res = addShare("https://cloud.example.org/s/$token", name = name)
        assertEquals(201, res.statusCode(), res.body())
        return ApiJson.decodeFromString(SourceDto.serializer(), res.body())
    }

    /** Connects an account through the login flow the way the web UI does and returns its source ID. */
    private fun connect(folder: String? = "/"): String {
        val started = frame.send("POST", "/api/v1/sources/login", """{"serverUrl":"https://cloud.example.org"}""")
        assertEquals(201, started.statusCode())
        val flow = ApiJson.decodeFromString(SourceLoginDto.serializer(), started.body())
        login.approved = true
        val done = ApiJson.decodeFromString(SourceLoginStateDto.serializer(), frame.get("/api/v1/sources/login/${flow.flowId}").body())
        assertEquals("done", done.state)
        val id = done.sourceId!!
        if (folder != null) assertEquals(200, frame.send("PATCH", "/api/v1/sources/$id", """{"folder":"$folder"}""").statusCode())
        return id
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

    private fun synced(id: String) = source(id).status.let { it.ok == true && !it.syncing }

    // --- List and access

    @Test
    fun `No sources at the start`() {
        assertEquals(emptyList<SourceDto>(), list())
        assertEquals(404, frame.get("/api/v1/sources/nope").statusCode())
    }

    @Test
    fun `Every endpoint needs a token`() {
        for ((method, path) in listOf(
            "GET" to "/api/v1/sources",
            "POST" to "/api/v1/sources",
            "POST" to "/api/v1/sources/login",
            "GET" to "/api/v1/sources/login/abc",
            "GET" to "/api/v1/sources/s00000001",
            "PATCH" to "/api/v1/sources/s00000001",
            "DELETE" to "/api/v1/sources/s00000001",
            "POST" to "/api/v1/sources/s00000001/sync",
        )) {
            val body = if (method == "GET" || method == "DELETE") null else "{}"
            assertEquals(401, frame.http.request(method, path, body).statusCode(), "$method $path")
        }
    }

    @Test
    fun `Every changing endpoint refuses a foreign origin and changes nothing`() {
        val share = shared("AbC123")
        for ((method, path) in listOf(
            "POST" to "/api/v1/sources",
            "POST" to "/api/v1/sources/login",
            "PATCH" to "/api/v1/sources/${share.id}",
            "DELETE" to "/api/v1/sources/${share.id}",
            "POST" to "/api/v1/sources/${share.id}/sync",
        )) {
            val body =
                if (method ==
                    "DELETE"
                ) {
                    null
                } else {
                    """{"kind":"nextcloud-share","link":"https://x.org/s/B2","serverUrl":"https://x.org"}"""
                }
            val res = frame.http.request(method, path, body, frame.auth + ("Origin" to "http://evil.example"))
            assertEquals(403, res.statusCode(), "$method $path")
        }
        assertEquals(listOf(share.id), list().map { it.id })
    }

    // --- Share links

    @Test
    fun `A share link is a source with its defaults and its first sync runs at once`() {
        clients.share("AbC123").remote.put("holiday/a.jpg", Fixtures.file("landscape.jpg").readBytes(), "e1")
        val res = addShare("https://cloud.example.org/index.php/s/AbC123/")
        assertEquals(201, res.statusCode())
        val added = ApiJson.decodeFromString(SourceDto.serializer(), res.body())
        assertTrue(Regex("s[0-9a-f]{8}").matches(added.id))
        assertEquals("nextcloud-share", added.kind)
        assertEquals("https://cloud.example.org", added.serverUrl)
        assertNull(added.user)
        assertEquals(SourceSettingsDto("Shared", true, "/", true, true, 20, 2000), added.settings, "named after the shared folder")
        assertTrue(added.status.syncing, "the first sync shows as running")
        assertFalse(res.body().contains("AbC123"), "no token in the answer")
        waitFor("the first sync") { synced(added.id) }
        val photo = photos().items.single()
        assertEquals(added.id, photo.source)
        assertEquals("holiday", photo.album)
        assertTrue(photo.id.startsWith(added.id + "-"))
        assertEquals(listOf(added.id), list().map { it.id })
    }

    @Test
    fun `A share is called after its folder, the host only if the server names none`() {
        clients.share("Fam1", name = "Familie 2025")
        clients.share("Anon2", name = "")
        val named = ApiJson.decodeFromString(SourceDto.serializer(), addShare("https://cloud.example.org/s/Fam1").body())
        val anonymous = ApiJson.decodeFromString(SourceDto.serializer(), addShare("https://cloud.example.org/s/Anon2").body())
        assertEquals("Familie 2025", named.settings.name)
        assertEquals("cloud.example.org", anonymous.settings.name)
    }

    @Test
    fun `A share gets the name it was given`() {
        assertEquals("Holiday 2025", shared("AbC123", name = "Holiday 2025").settings.name)
        clients.share("Other1")
        val bad = addShare("https://cloud.example.org/s/Other1", name = " ")
        assertEquals(400, bad.statusCode())
        assertEquals(setOf("name"), fields(bad.body()))
    }

    @Test
    fun `A link that is no share is a 400 at the link and stores nothing`() {
        for (link in listOf("https://cloud.example.org/apps/files/", "ftp://cloud.example.org/s/AbC123", "AbC123")) {
            val res = addShare(link)
            assertEquals(400, res.statusCode(), link)
            assertEquals("validation_failed", errorCode(res.body()))
            assertEquals(setOf("link"), fields(res.body()))
        }
        val wrongKind = frame.send("POST", "/api/v1/sources", """{"kind":"nextcloud-account","link":"https://cloud.example.org/s/A1"}""")
        assertEquals(400, wrongKind.statusCode())
        assertEquals(setOf("kind"), fields(wrongKind.body()))
        assertEquals(emptyList<SourceDto>(), list())
    }

    @Test
    fun `A share that does not exist is a 400 at the link`() {
        val res = addShare("https://cloud.example.org/s/Nope42")
        assertEquals(400, res.statusCode())
        assertEquals("share_not_found", errorCode(res.body()))
        assertEquals(setOf("link"), fields(res.body()))
        assertEquals(emptyList<SourceDto>(), list())
    }

    @Test
    fun `The share of a single file is refused at the link`() {
        clients.share("File42", isFolder = false)
        val res = addShare("https://cloud.example.org/s/File42")
        assertEquals(400, res.statusCode())
        assertEquals("share_not_a_folder", errorCode(res.body()))
        assertEquals(setOf("link"), fields(res.body()))
        assertEquals(emptyList<SourceDto>(), list())
    }

    @Test
    fun `A file drop that only accepts uploads is refused at the link`() {
        clients.share("Drop42", uploadOnly = true)
        val res = addShare("https://cloud.example.org/s/Drop42")
        assertEquals(400, res.statusCode())
        assertEquals("share_not_readable", errorCode(res.body()))
        assertEquals(setOf("link"), fields(res.body()))
        assertEquals(emptyList<SourceDto>(), list())
    }

    @Test
    fun `A protected share needs its password, a wrong one is refused, the right one works`() {
        clients.share("Prot42", password = "right-pw")
        val missing = addShare("https://cloud.example.org/s/Prot42")
        assertEquals(400, missing.statusCode())
        assertEquals("share_password_required", errorCode(missing.body()))
        assertEquals(setOf("password"), fields(missing.body()))
        val wrong = addShare("https://cloud.example.org/s/Prot42", password = "wrong-pw")
        assertEquals(400, wrong.statusCode())
        assertEquals("share_password_wrong", errorCode(wrong.body()))
        assertEquals(setOf("password"), fields(wrong.body()))
        assertEquals(emptyList<SourceDto>(), list(), "nothing is stored")
        val right = addShare("https://cloud.example.org/s/Prot42", password = "right-pw")
        assertEquals(201, right.statusCode())
        assertFalse(right.body().contains("right-pw"))
        assertFalse(frame.get("/api/v1/sources").body().contains("right-pw"))
    }

    @Test
    fun `A server that does not answer is a 502 and stores nothing`() {
        clients.shareFailure = SourceException(SourceException.Kind.UNREACHABLE, "The server cloud.example.org was not found")
        val res = addShare("https://cloud.example.org/s/AbC123")
        assertEquals(502, res.statusCode())
        assertEquals("nextcloud_unreachable", errorCode(res.body()))
        assertEquals(emptyList<SourceDto>(), list())
    }

    @Test
    fun `The same share twice is a 409 source_exists, also as http or behind index php`() {
        shared("AbC123")
        for (link in listOf("https://cloud.example.org/s/AbC123", "http://Cloud.example.org/index.php/s/AbC123")) {
            val res = addShare(link)
            assertEquals(409, res.statusCode(), link)
            assertEquals("source_exists", errorCode(res.body()))
        }
        assertEquals(1, list().size)
    }

    @Test
    fun `A ninth source is a 409 too_many_sources, for a share and for a new login`() {
        repeat(8) { shared("Share$it") }
        clients.share("Ninth9")
        val share = addShare("https://cloud.example.org/s/Ninth9")
        assertEquals(409, share.statusCode())
        assertEquals("too_many_sources", errorCode(share.body()))
        val login = frame.send("POST", "/api/v1/sources/login", """{"serverUrl":"https://cloud.example.org"}""")
        assertEquals(409, login.statusCode())
        assertEquals("too_many_sources", errorCode(login.body()))
        assertEquals(8, list().size)
    }

    // --- Login flow

    @Test
    fun `The login is pending until the user approves, then the account is a source`() {
        val started = frame.send("POST", "/api/v1/sources/login", """{"serverUrl":"https://cloud.example.org/"}""")
        val flow = ApiJson.decodeFromString(SourceLoginDto.serializer(), started.body())
        assertEquals(listOf("https://cloud.example.org"), login.started, "the address is normalized")
        assertTrue(flow.loginUrl.endsWith("/index.php/login/v2/flow/abc"))
        val pending =
            ApiJson.decodeFromString(SourceLoginStateDto.serializer(), frame.get("/api/v1/sources/login/${flow.flowId}").body())
        assertEquals("pending", pending.state)
        assertNull(pending.sourceId)
        assertEquals(emptyList<SourceDto>(), list())
        login.approved = true
        val done = ApiJson.decodeFromString(SourceLoginStateDto.serializer(), frame.get("/api/v1/sources/login/${flow.flowId}").body())
        assertEquals("done", done.state)
        assertEquals("Anna", done.user)
        val s = source(done.sourceId!!)
        assertEquals("nextcloud-account", s.kind)
        assertEquals("Anna", s.user)
        assertEquals("https://cloud.example.org", s.serverUrl)
        assertEquals("", s.settings.folder, "an account waits for its folder")
        assertFalse(s.status.syncing)
        // The flow is used up.
        assertEquals(
            "expired",
            ApiJson.decodeFromString(SourceLoginStateDto.serializer(), frame.get("/api/v1/sources/login/${flow.flowId}").body()).state,
        )
    }

    @Test
    fun `Logging in again to the same account renews it and keeps photos and settings, another user is a second source`() {
        val id = connect()
        frame.send("PATCH", "/api/v1/sources/$id", """{"intervalMinutes":45}""")
        clients.remote.put("a.jpg", Fixtures.file("landscape.jpg").readBytes(), "e1")
        frame.send("POST", "/api/v1/sources/$id/sync")
        waitFor("the sync") { synced(id) }
        login.approved = false
        assertEquals(id, connect(folder = null))
        assertEquals(1, list().size)
        assertEquals(45, source(id).settings.intervalMinutes)
        assertEquals("/", source(id).settings.folder)
        assertEquals(1, photos().total)
        login.approved = false
        login.userId = "bert"
        val second = connect(folder = null)
        assertTrue(second != id)
        assertEquals(2, list().size)
    }

    @Test
    fun `An unknown or old flow is expired`() {
        val polled = frame.get("/api/v1/sources/login/nope")
        assertEquals(200, polled.statusCode())
        assertEquals("expired", ApiJson.decodeFromString(SourceLoginStateDto.serializer(), polled.body()).state)
    }

    @Test
    fun `A wrong address is a 400 at the field and an unreachable server a 502`() {
        val bad = frame.send("POST", "/api/v1/sources/login", """{"serverUrl":"ftp://cloud.example.org"}""")
        assertEquals(400, bad.statusCode())
        assertEquals(setOf("serverUrl"), fields(bad.body()))
        login.startFailure = SourceException(SourceException.Kind.UNREACHABLE, "The server cloud.example.org was not found")
        val down = frame.send("POST", "/api/v1/sources/login", """{"serverUrl":"https://cloud.example.org"}""")
        assertEquals(502, down.statusCode())
        assertEquals("nextcloud_unreachable", errorCode(down.body()))
        assertEquals(emptyList<SourceDto>(), list(), "nothing is stored")
    }

    @Test
    fun `The app password is in no response`() {
        connect()
        for (path in listOf("/api/v1/sources", "/api/v1/logs")) {
            assertFalse(frame.get(path).body().contains(login.appPassword), path)
        }
    }

    // --- Settings, sync, removal

    @Test
    fun `Settings change partially and wrong values name the field`() {
        val id = shared("AbC123")
        val ok = frame.send("PATCH", "/api/v1/sources/${id.id}", """{"folder":"Photos/Family","intervalMinutes":45,"name":"Family"}""")
        assertEquals(200, ok.statusCode())
        val s = ApiJson.decodeFromString(SourceDto.serializer(), ok.body()).settings
        assertEquals("/Photos/Family", s.folder)
        assertEquals(45, s.intervalMinutes)
        assertEquals("Family", s.name)
        assertTrue(s.removeVanished)
        val bad = frame.send("PATCH", "/api/v1/sources/${id.id}", """{"intervalMinutes":2}""")
        assertEquals(400, bad.statusCode())
        assertEquals(setOf("intervalMinutes"), fields(bad.body()))
        assertEquals(45, source(id.id).settings.intervalMinutes)
        assertEquals(404, frame.send("PATCH", "/api/v1/sources/nope", """{"intervalMinutes":20}""").statusCode())
        assertEquals(400, frame.send("PATCH", "/api/v1/sources/${id.id}", "[]").statusCode())
    }

    @Test
    fun `An account without a folder syncs nothing and says why`() {
        val id = connect(folder = null)
        clients.remote.put("a.jpg", Fixtures.file("landscape.jpg").readBytes(), "e1")
        val res = frame.send("POST", "/api/v1/sources/$id/sync")
        assertEquals(409, res.statusCode())
        assertEquals("source_no_folder", errorCode(res.body()))
        Thread.sleep(300)
        assertEquals(0, photos().total)
        assertEquals(emptyList<String>(), clients.remote.downloads)
        assertEquals(404, frame.send("POST", "/api/v1/sources/nope/sync").statusCode())
    }

    @Test
    fun `A sync on request runs, reports its result, and a photo of a source cannot be deleted`() {
        val id = connect()
        clients.remote.put("holiday/a.jpg", Fixtures.file("landscape.jpg").readBytes(), "e1")
        val res = frame.send("POST", "/api/v1/sources/$id/sync")
        assertEquals(202, res.statusCode())
        waitFor("the sync") { synced(id) }
        val status = source(id).status
        assertEquals(1, status.added)
        assertNull(status.error)
        assertTrue(status.lastSyncAt != null)
        val photo = photos().items.single()
        assertEquals("holiday/a.jpg" to id, photo.path to photo.source)
        assertEquals(409, frame.send("DELETE", "/api/v1/photos/${photo.id}").statusCode())
    }

    @Test
    fun `A failed sync shows a readable error and keeps the photos`() {
        val share = shared("AbC123")
        clients.shares
            .getValue("AbC123")
            .remote
            .put("a.jpg", Fixtures.file("landscape.jpg").readBytes(), "e1")
        frame.send("POST", "/api/v1/sources/${share.id}/sync")
        waitFor("the first sync") { synced(share.id) && photos().total == 1 }
        clients.shares
            .getValue("AbC123")
            .remote.listFailure =
            SourceException(SourceException.Kind.UNREACHABLE, "The server cloud.example.org did not answer in time")
        frame.send("POST", "/api/v1/sources/${share.id}/sync")
        waitFor("the failure") { source(share.id).status.let { it.ok == false && !it.syncing } }
        assertEquals("The server cloud.example.org did not answer in time", source(share.id).status.error)
        assertEquals(1, photos().total)
    }

    @Test
    fun `Two sources sync on their own, and removing one leaves the other and the local photos`() {
        val first = shared("First1")
        val second = shared("Second2")
        clients.shares
            .getValue("First1")
            .remote
            .put("holiday/a.jpg", Fixtures.file("landscape.jpg").readBytes(), "e1")
        clients.shares
            .getValue("Second2")
            .remote
            .put("holiday/b.jpg", Fixtures.file("landscape.jpg").readBytes(), "e2")
        Fixtures.copy("image.png", File(frame.photos, "mine.png"))
        frame.scan()
        frame.send("POST", "/api/v1/sources/${first.id}/sync")
        waitFor("the first source") { synced(first.id) && photos().total >= 2 }
        frame.send("POST", "/api/v1/sources/${second.id}/sync")
        waitFor("the second source") { synced(second.id) && photos().total == 3 }
        val albums = ApiJson.decodeFromString(AlbumListDto.serializer(), frame.get("/api/v1/albums").body()).albums
        assertEquals(2, albums.single { it.name == "holiday" }.count, "one album from both sources")

        assertEquals(204, frame.send("DELETE", "/api/v1/sources/${first.id}").statusCode())
        assertEquals(listOf(second.id), list().map { it.id })
        assertEquals(setOf("mine.png", "holiday/b.jpg"), photos().items.map { it.path }.toSet())
        assertEquals(true, source(second.id).status.ok)
        assertEquals(404, frame.send("DELETE", "/api/v1/sources/${first.id}").statusCode())
        assertEquals(404, frame.get("/api/v1/sources/${first.id}").statusCode())
    }

    @Test
    fun `Removing an account revokes its app password and removes its photos`() {
        val id = connect()
        clients.remote.put("a.jpg", Fixtures.file("landscape.jpg").readBytes(), "e1")
        frame.send("POST", "/api/v1/sources/$id/sync")
        waitFor("the sync") { synced(id) && photos().total == 1 }
        assertEquals(204, frame.send("DELETE", "/api/v1/sources/$id").statusCode())
        waitFor("the revoke") { login.revoked == 1 }
        assertEquals(0, photos().total)
        assertEquals(emptyList<SourceDto>(), list())
    }
}
