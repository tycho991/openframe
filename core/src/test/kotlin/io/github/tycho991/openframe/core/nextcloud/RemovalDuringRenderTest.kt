package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.api.PhotoPageDto
import io.github.tycho991.openframe.core.api.SourceDto
import io.github.tycho991.openframe.core.api.SourceLoginDto
import io.github.tycho991.openframe.core.api.SourceLoginStateDto
import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.slideshow.SlideState
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.TestFrame
import io.github.tycho991.openframe.core.testing.waitFor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Spec `remote-sources`: Bounded downloads › Source removed during a sync, and Rendering does not wait for downloads:
 * a whole frame with the real client, a source removed while an original downloads for a rendering.
 */
class RemovalDuringRenderTest {
    @TempDir
    lateinit var dir: File

    private val nextcloud = FakeNextcloud()
    private lateinit var frame: TestFrame

    @BeforeEach
    fun start() {
        nextcloud.start()
        for (i in 1..3) Fixtures.copy("landscape.jpg", File(dir, "photos/local$i.jpg"))
        frame =
            TestFrame(
                dir,
                nextcloudClients = OkHttpNextcloudClients { NextcloudHttp(NextcloudHttp.defaultClient(readTimeoutSeconds = 10)) },
            )
    }

    @AfterEach
    fun stop() {
        frame.close()
        nextcloud.stop()
    }

    private fun photos() = ApiJson.decodeFromString(PhotoPageDto.serializer(), frame.get("/api/v1/photos").body())

    /** Connects the account of the fake server with the folder `P` and syncs it. */
    private fun connected(): String {
        for (i in 1..3) nextcloud.put("P/remote$i.jpg", Fixtures.file("landscape.jpg").readBytes() + ByteArray(200_000), "e$i")
        val started = frame.send("POST", "/api/v1/sources/login", """{"serverUrl":"${nextcloud.baseUrl}"}""")
        assertEquals(201, started.statusCode(), started.body())
        val flow = ApiJson.decodeFromString(SourceLoginDto.serializer(), started.body())
        nextcloud.approved = true
        val done = ApiJson.decodeFromString(SourceLoginStateDto.serializer(), frame.get("/api/v1/sources/login/${flow.flowId}").body())
        assertEquals("done", done.state, done.error)
        val id = done.sourceId!!
        assertEquals(200, frame.send("PATCH", "/api/v1/sources/$id", """{"folder":"/P"}""").statusCode())
        frame.send("POST", "/api/v1/sources/$id/sync")
        waitFor(15_000, "the first sync") {
            ApiJson.decodeFromString(SourceDto.serializer(), frame.get("/api/v1/sources/$id").body()).status.ok == true &&
                photos().total == 6
        }
        return id
    }

    @Test
    fun `A source removed while an original downloads for a rendering ends it, and the slideshow goes on`() {
        frame.scan()
        val id = connected()
        val remote = photos().items.first { it.source == id }
        // The original is gone from the cache, and the server sends it back slowly.
        File(frame.dirs.cache, "sources/$id").listFiles().orEmpty().forEach { it.delete() }
        nextcloud.pacing = FakeNextcloud.Pacing(64, { method, _ -> method == "GET" }) { Thread.sleep(20) }
        val rendering = Thread { frame.get("/api/v1/photos/${remote.id}/image?size=full") }.apply { start() }
        waitFor(
            10_000,
            "the download for the rendering",
        ) { synchronized(nextcloud.requests) { nextcloud.requests.any { it.startsWith("GET") } } }

        // Local photos render meanwhile.
        val local = photos().items.first { it.source == "local" }
        assertEquals(200, frame.get("/api/v1/photos/${local.id}/image?size=full").statusCode())

        assertEquals(204, frame.send("DELETE", "/api/v1/sources/$id").statusCode())
        rendering.join(10_000)
        assertFalse(rendering.isAlive, "the rendering ended with the removal")
        assertFalse(File(frame.dirs.cache, "sources/$id").walk().any { it.name.endsWith(".part") })

        // The slideshow keeps changing photos.
        waitFor(10_000, "a photo on screen") { frame.core.slideshow.state.value is SlideState.Showing }
        repeat(3) {
            val before = (frame.core.slideshow.state.value as SlideState.Showing).seq
            frame.core.slideshow.next()
            waitFor(
                10_000,
                "the next photo",
            ) { (frame.core.slideshow.state.value as? SlideState.Showing)?.seq?.let { it > before } == true }
        }
    }
}
