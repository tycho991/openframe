package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.api.SourceDto
import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.nextcloud.FakeClients
import io.github.tycho991.openframe.core.slideshow.SlideState
import io.github.tycho991.openframe.core.testing.FakePlatform
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.TestFrame
import io.github.tycho991.openframe.core.testing.waitFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `photo-library`: Storage reserve, Going below the storage reserve; a whole frame with a nearly full volume. */
class StorageReserveFrameTest {
    @TempDir
    lateinit var dir: File

    private val mib = 1024L * 1024

    /** A reference frame (16 GiB, reserve 600 MiB) with [freeMib] free. */
    private fun platform(freeMib: Long) = FakePlatform(total = 16 * 1024 * mib, free = freeMib * mib)

    /** Spec `photo-library`: Going below the storage reserve › Local photos below the reserve. */
    @Test
    fun `Below the reserve local photos keep changing, each rendered when it is due`() {
        val photos = File(dir, "photos")
        for (i in 1..6) Fixtures.copy("landscape.jpg", File(photos, "p$i.jpg"))
        TestFrame(dir, platform = platform(300)).use { frame ->
            val renders = File(frame.dirs.cache, RenderCache.FOLDER)
            waitFor { frame.core.slideshow.state.value is SlideState.Showing }
            val shown = mutableListOf((frame.core.slideshow.state.value as SlideState.Showing).photo.id)
            repeat(3) {
                assertEquals(200, frame.send("POST", "/api/v1/slideshow", """{"action":"next"}""").statusCode())
                // Shuffled, each once per pass: a photo not shown yet.
                waitFor(message = "the next photo") { (frame.core.slideshow.state.value as SlideState.Showing).photo.id !in shown }
                shown += (frame.core.slideshow.state.value as SlideState.Showing).photo.id
                // Nothing ahead of time: only photos that were shown have a rendering.
                Thread.sleep(200)
                val rendered = renders.list()!!.map { it.substringBefore("-v") }.toSet()
                assertTrue(shown.containsAll(rendered), "rendered $rendered, shown $shown")
            }
            assertEquals(6, frame.core.library.index.library.value.photos.size, "the photos stay in the library")
        }
    }

    /**
     * Spec `photo-library`: Going below the storage reserve › Remote photo without its original below the reserve,
     * › Storage almost full; through the whole chain from the image request to the download.
     */
    @Test
    fun `An image a client asks for downloads its original below the reserve, but not below 100 MiB`() {
        val clients = FakeClients()
        val remote = clients.share("AbC123").remote
        val bytes = Fixtures.file("landscape.jpg").readBytes()
        remote.put("a.jpg", bytes, "e1")
        remote.put("b.jpg", bytes, "e2")
        val platform = platform(2000)
        TestFrame(dir, platform = platform, nextcloudClients = clients).use { frame ->
            val res = frame.send("POST", "/api/v1/sources", """{"kind":"nextcloud-share","link":"https://cloud.example.org/s/AbC123"}""")
            val source = ApiJson.decodeFromString(SourceDto.serializer(), res.body()).id
            waitFor(10_000, "the first sync") { frame.core.library.index.library.value.photos.size == 2 }
            val ids = listOf("a.jpg", "b.jpg").map { PhotoEntry.idFor(source, it) }
            // Hidden, so that the slideshow asks for neither; their originals evicted.
            ids.forEach { assertEquals(200, frame.send("PATCH", "/api/v1/photos/$it", """{"hidden":true}""").statusCode()) }
            File(frame.dirs.cache, "sources/$source").listFiles()!!.forEach { it.delete() }
            remote.downloads.clear()
            platform.free = 100 * mib + bytes.size + 1000
            assertEquals(200, frame.get("/api/v1/photos/${ids[0]}/image?size=full").statusCode())
            assertEquals(listOf("a.jpg"), remote.downloads.toList())
            platform.free = 100 * mib + bytes.size - 1
            assertEquals(503, frame.get("/api/v1/photos/${ids[1]}/image?size=full").statusCode())
            assertEquals(listOf("a.jpg"), remote.downloads.toList(), "no request below the floor")
            assertEquals(2, frame.core.library.index.library.value.photos.size, "the photo stays in the library")
        }
    }

    /** Spec `remote-sources`: Storage of the originals › Budgets larger than the storage, with the frame's own free storage. */
    @Test
    fun `A sync below the reserve downloads nothing and fails with storage`() {
        val clients = FakeClients()
        clients.share("AbC123").remote.put("a.jpg", Fixtures.file("landscape.jpg").readBytes(), "e1")
        TestFrame(dir, platform = platform(500), nextcloudClients = clients).use { frame ->
            val res = frame.send("POST", "/api/v1/sources", """{"kind":"nextcloud-share","link":"https://cloud.example.org/s/AbC123"}""")
            assertEquals(201, res.statusCode(), res.body())
            val id = ApiJson.decodeFromString(SourceDto.serializer(), res.body()).id
            waitFor(10_000, "the first sync") {
                ApiJson.decodeFromString(SourceDto.serializer(), frame.get("/api/v1/sources/$id").body()).status.errorCode != null
            }
            val status = ApiJson.decodeFromString(SourceDto.serializer(), frame.get("/api/v1/sources/$id").body()).status
            assertEquals("storage", status.errorCode)
            assertEquals(
                emptyList<String>(),
                clients.shares
                    .getValue("AbC123")
                    .remote.downloads,
            )
            assertEquals(0, frame.core.library.index.library.value.photos.size)
        }
    }
}
