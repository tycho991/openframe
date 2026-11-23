package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.api.SourceDto
import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.nextcloud.FakeClients
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.TestFrame
import io.github.tycho991.openframe.core.testing.waitFor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Spec `photo-library`: Rendered sizes follow the index; spec `remote-sources`: Vanished photos. Every way out of the
 * index removes the renderings through the index's notification (design decision 4 of `library-performance`).
 */
class RenderedSizesFollowIndexTest {
    @TempDir
    lateinit var dir: File

    private val clients = FakeClients()
    private val frame by lazy { TestFrame(dir, nextcloudClients = clients) }

    @AfterEach
    fun close() = frame.close()

    private val renders get() = File(frame.dirs.cache, RenderCache.FOLDER)

    private fun renderingsOf(id: String) = renders.list().orEmpty().filter { it.startsWith("$id-") }

    private fun render(id: String) {
        for (size in listOf("thumb", "display")) {
            assertEquals(200, frame.get("/api/v1/photos/$id/image?size=$size").statusCode(), size)
        }
        assertEquals(2, renderingsOf(id).size)
    }

    private fun idle() = frame.core.cache.awaitDeletions()

    @Test
    fun `File deleted over USB`() {
        val file = Fixtures.copy("landscape.jpg", File(frame.photos, "a.jpg"))
        frame.scan()
        val id = PhotoEntry.idFor("local", "a.jpg")
        render(id)
        file.delete()
        frame.scan()
        idle()
        assertEquals(emptyList<String>(), renderingsOf(id))
    }

    @Test
    fun `File replaced by a damaged one`() {
        val file = Fixtures.copy("landscape.jpg", File(frame.photos, "a.jpg"))
        frame.scan()
        val id = PhotoEntry.idFor("local", "a.jpg")
        render(id)
        Fixtures.copy("broken.jpg", file)
        file.setLastModified(file.lastModified() + 10_000)
        frame.scan()
        assertEquals(
            listOf("a.jpg"),
            frame.core.library.index.library.value.skipped
                .map { it.path },
        )
        idle()
        assertEquals(emptyList<String>(), renderingsOf(id))
    }

    private fun shared(): String {
        clients.share("AbC123").remote.put("a.jpg", Fixtures.file("landscape.jpg").readBytes(), "e1")
        val res = frame.send("POST", "/api/v1/sources", """{"kind":"nextcloud-share","link":"https://cloud.example.org/s/AbC123"}""")
        assertEquals(201, res.statusCode(), res.body())
        val id = ApiJson.decodeFromString(SourceDto.serializer(), res.body()).id
        waitFor(SYNC_TIMEOUT_MS, "the first sync") {
            frame.core.library.index.library.value.photos.values
                .any { it.source == id }
        }
        return id
    }

    private fun sync(source: String) {
        val before = frame.core.library.index.library.value.version
        assertEquals(202, frame.send("POST", "/api/v1/sources/$source/sync").statusCode())
        waitFor(SYNC_TIMEOUT_MS, "the sync") {
            frame.core.library.index.library.value.version > before &&
                !frame.core.sources
                    .status(source)
                    .syncing
        }
    }

    private fun originalsOf(source: String) = File(frame.dirs.cache, "sources/$source").list().orEmpty().toList()

    @Test
    fun `Photo deleted at the source`() {
        val source = shared()
        val id = PhotoEntry.idFor(source, "a.jpg")
        render(id)
        assertEquals(1, originalsOf(source).size)
        clients.shares
            .getValue("AbC123")
            .remote
            .remove("a.jpg")
        sync(source)
        assertFalse(
            frame.core.library.index.library.value.photos
                .containsKey(id),
        )
        idle()
        assertEquals(emptyList<String>(), renderingsOf(id))
        waitFor(message = "the original removed") { originalsOf(source).isEmpty() }
    }

    @Test
    fun `A remote photo whose new version is skipped loses its renderings and its original`() {
        val source = shared()
        val id = PhotoEntry.idFor(source, "a.jpg")
        render(id)
        clients.shares
            .getValue("AbC123")
            .remote
            .put("a.jpg", Fixtures.file("broken.jpg").readBytes(), "e2")
        sync(source)
        assertTrue(
            frame.core.library.index.library.value.skipped
                .any { it.path == "a.jpg" },
        )
        idle()
        assertEquals(emptyList<String>(), renderingsOf(id))
        waitFor(message = "the old original removed") { originalsOf(source).isEmpty() }
    }

    private companion object {
        const val SYNC_TIMEOUT_MS = 10_000L
    }
}
