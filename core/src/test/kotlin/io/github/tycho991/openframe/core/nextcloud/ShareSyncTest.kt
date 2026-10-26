package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.api.PhotoDto
import io.github.tycho991.openframe.core.api.PhotoPageDto
import io.github.tycho991.openframe.core.api.SourceDto
import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.library.Crop
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.TestFrame
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Spec `remote-sources`: A failed sync loses nothing (share withdrawn); `nextcloud-source`: Password of a share
 * (changed later). The real client against the fake server, through the API of a whole frame.
 */
class ShareSyncTest {
    @TempDir
    lateinit var dir: File

    private val nextcloud = FakeNextcloud()
    private lateinit var frame: TestFrame
    private lateinit var id: String

    @BeforeEach
    fun start() {
        nextcloud.start()
        frame =
            TestFrame(dir, nextcloudClients = OkHttpNextcloudClients { NextcloudHttp(NextcloudHttp.defaultClient(readTimeoutSeconds = 5)) })
        nextcloud.put("Shared/holiday/a.jpg", Fixtures.file("landscape.jpg").readBytes(), "e1")
        nextcloud.put("Shared/b.jpg", Fixtures.file("landscape.jpg").readBytes(), "e2")
        nextcloud.shares["AbC123"] = FakeNextcloud.Share("Shared", password = "pw-1")
        val added =
            frame.send(
                "POST",
                "/api/v1/sources",
                """{"kind":"nextcloud-share","link":"${nextcloud.baseUrl}/s/AbC123","password":"pw-1"}""",
            )
        assertEquals(201, added.statusCode(), added.body())
        id = ApiJson.decodeFromString(SourceDto.serializer(), added.body()).id
        waitForResult()
        assertEquals(true, status().ok)
        assertEquals(2, photoCount())
    }

    @AfterEach
    fun stop() {
        frame.close()
        nextcloud.stop()
    }

    private fun status() = ApiJson.decodeFromString(SourceDto.serializer(), frame.get("/api/v1/sources/$id").body()).status

    private fun photoCount() = ApiJson.decodeFromString(PhotoPageDto.serializer(), frame.get("/api/v1/photos").body()).total

    private fun waitForResult() {
        val deadline = System.currentTimeMillis() + 10_000
        while (status().syncing || status().ok == null) {
            check(System.currentTimeMillis() < deadline) { "the sync did not end" }
            Thread.sleep(25)
        }
    }

    private fun syncAndWait() {
        assertEquals(202, frame.send("POST", "/api/v1/sources/$id/sync").statusCode())
        waitForResult()
    }

    @Test
    fun `A section on a photo of a share writes nothing to the source`() {
        // Spec `photo-library`, "Section of a photo" › "Photo of a share".
        val photo =
            ApiJson
                .decodeFromString(PhotoPageDto.serializer(), frame.get("/api/v1/photos").body())
                .items
                .first()
        val before = synchronized(nextcloud.requests) { nextcloud.requests.toList() }
        val res = frame.send("PATCH", "/api/v1/photos/${photo.id}", """{"crop":{"x":0.5,"y":0.2,"zoom":1.5}}""")
        assertEquals(200, res.statusCode(), res.body())
        assertEquals(Crop(0.5, 0.2, 1.5), ApiJson.decodeFromString(PhotoDto.serializer(), res.body()).crop)
        assertEquals(before, synchronized(nextcloud.requests) { nextcloud.requests.toList() }, "no request reached the server")
    }

    @Test
    fun `A share withdrawn while the server answers 401 shows as not found and keeps the photos`() {
        nextcloud.shares.remove("AbC123")
        syncAndWait()
        assertEquals(false, status().ok)
        assertTrue(status().error.orEmpty().contains("share") && status().error.orEmpty().contains("not found"), status().error)
        assertEquals(2, photoCount())
    }

    @Test
    fun `A share withdrawn while the server answers 404 shows as not found and keeps the photos`() {
        nextcloud.shares.remove("AbC123")
        nextcloud.faults["/public.php/webdav"] = 404
        syncAndWait()
        assertEquals(false, status().ok)
        assertTrue(status().error.orEmpty().contains("share") && status().error.orEmpty().contains("not found"), status().error)
        assertEquals(2, photoCount())
    }

    @Test
    fun `A password changed by the owner shows as refused and keeps the photos`() {
        nextcloud.shares.getValue("AbC123").password = "pw-2"
        syncAndWait()
        assertEquals(false, status().ok)
        assertEquals("The share refused the password (HTTP 401)", status().error)
        assertEquals(2, photoCount())
    }

    @Test
    fun `A share turned into a file drop shows as not readable and keeps the photos`() {
        nextcloud.shares.getValue("AbC123").uploadOnly = true
        syncAndWait()
        assertEquals(false, status().ok)
        assertEquals("The share only accepts uploads (a file drop); the frame cannot read it", status().error)
        assertEquals(2, photoCount())
    }

    @Test
    fun `A folder of the share that is gone is named as the folder`() {
        assertEquals(200, frame.send("PATCH", "/api/v1/sources/$id", """{"folder":"/nowhere"}""").statusCode())
        syncAndWait()
        assertEquals(false, status().ok)
        assertEquals("The folder /nowhere was not found (HTTP 404)", status().error)
        assertEquals(2, photoCount(), "a listing that failed removes nothing")
    }
}
