package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.api.PhotoPageDto
import io.github.tycho991.openframe.core.api.SourceDto
import io.github.tycho991.openframe.core.api.SourceLoginDto
import io.github.tycho991.openframe.core.api.SourceLoginStateDto
import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.TestFrame
import io.github.tycho991.openframe.core.testing.waitFor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Spec `nextcloud-source`: Connect with the login flow, and `remote-sources`: Sync of a folder, for an account with the
 * real clients against the fake server through the API: the folder chosen is the one that syncs, not the account's
 * root (tests-quality-8).
 */
class AccountSyncTest {
    @TempDir
    lateinit var dir: File

    private val nextcloud = FakeNextcloud()
    private lateinit var frame: TestFrame

    @BeforeEach
    fun start() {
        nextcloud.start()
        frame =
            TestFrame(dir, nextcloudClients = OkHttpNextcloudClients { NextcloudHttp(NextcloudHttp.defaultClient(readTimeoutSeconds = 5)) })
    }

    @AfterEach
    fun stop() {
        frame.close()
        nextcloud.stop()
    }

    private fun photos() = ApiJson.decodeFromString(PhotoPageDto.serializer(), frame.get("/api/v1/photos").body())

    @Test
    fun `An account syncs the folder chosen for it and nothing else`() {
        nextcloud.put("Photos/a.jpg", Fixtures.file("landscape.jpg").readBytes(), "e1")
        nextcloud.put("Photos/holiday/b.jpg", Fixtures.file("landscape.jpg").readBytes(), "e2")
        nextcloud.put("Audio/cover.jpg", Fixtures.file("landscape.jpg").readBytes(), "e3")
        nextcloud.put("top.jpg", Fixtures.file("landscape.jpg").readBytes(), "e4")

        val started = frame.send("POST", "/api/v1/sources/login", """{"serverUrl":"${nextcloud.baseUrl}"}""")
        assertEquals(201, started.statusCode(), started.body())
        val flow = ApiJson.decodeFromString(SourceLoginDto.serializer(), started.body())
        nextcloud.approved = true
        val done = ApiJson.decodeFromString(SourceLoginStateDto.serializer(), frame.get("/api/v1/sources/login/${flow.flowId}").body())
        assertEquals("done", done.state, done.error)
        val id = done.sourceId!!
        assertEquals(200, frame.send("PATCH", "/api/v1/sources/$id", """{"folder":"/Photos"}""").statusCode())
        frame.send("POST", "/api/v1/sources/$id/sync")
        waitFor(15_000, "the sync") {
            ApiJson.decodeFromString(SourceDto.serializer(), frame.get("/api/v1/sources/$id").body()).status.let {
                it.ok == true &&
                    !it.syncing
            }
        }

        assertEquals(setOf("a.jpg", "holiday/b.jpg"), photos().items.map { it.path }.toSet())
        val dav = synchronized(nextcloud.requests) { nextcloud.requests.filter { "/remote.php/dav/" in it } }
        assertTrue(dav.isNotEmpty())
        assertTrue(dav.all { it.substringAfter(' ').startsWith("/remote.php/dav/files/anna/Photos") }, "$dav")
    }
}
