package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.RenderCache
import io.github.tycho991.openframe.core.nextcloud.FakeClients
import io.github.tycho991.openframe.core.sources.SourceException
import io.github.tycho991.openframe.core.testing.TestFrame
import io.github.tycho991.openframe.core.testing.waitFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Spec `remote-sources`: Remove a source › Source with many photos, › App ends during the deletion (design decision 11 of
 * `library-performance`), through the API with the contract check.
 */
class SourceRemovalTest {
    @TempDir
    lateinit var dir: File

    private val clients = FakeClients()
    private val share = clients.share("AbC123")
    private val renders get() = File(File(dir, "cache"), RenderCache.FOLDER)

    private fun originals(source: String) = File(File(dir, "cache"), "sources/$source")

    private fun photos(frame: TestFrame) = ApiJson.decodeFromString(PhotoPageDto.serializer(), frame.get("/api/v1/photos").body()).total

    /** A share whose listing fails, so that no sync changes its 1,000 photos, with an original and a rendering each. */
    private fun sourceWithManyPhotos(): Pair<String, List<PhotoEntry>> {
        share.remote.listFailure = SourceException(SourceException.Kind.UNREACHABLE, "The server cloud.example.org is not reachable")
        val (source, entries) =
            TestFrame(dir, nextcloudClients = clients).use { frame ->
                val res =
                    frame.send(
                        "POST",
                        "/api/v1/sources",
                        """{"kind":"nextcloud-share","link":"https://cloud.example.org/s/AbC123"}""",
                    )
                assertEquals(201, res.statusCode(), res.body())
                val source = ApiJson.decodeFromString(SourceDto.serializer(), res.body()).id
                val entries =
                    (0 until 1000).map {
                        PhotoEntry(
                            PhotoEntry.idFor(source, "p$it.jpg"),
                            source,
                            "p$it.jpg",
                            400,
                            300,
                            0,
                            10,
                            1,
                            1,
                            etag = "e$it",
                        )
                    }
                frame.core.library.index
                    .edit { edit -> entries.forEach(edit::put) }
                source to entries
            }
        // Written while the frame is stopped: the render cache finds them at its start.
        renders.mkdirs()
        originals(source).mkdirs()
        for (entry in entries) {
            File(renders, "${entry.id}-v1-400x300.jpg").writeBytes(ByteArray(10))
            File(originals(source), "${entry.id}.jpg").writeBytes(ByteArray(10))
        }
        return source to entries
    }

    @Test
    fun `Removing a source with 1,000 photos answers without waiting for its files`() {
        val (source, _) = sourceWithManyPhotos()
        TestFrame(dir, nextcloudClients = clients).use { frame ->
            assertEquals(1000, photos(frame))
            assertEquals(1000, renders.list()!!.count { it.startsWith("$source-") })
            val hold = CountDownLatch(1)
            val held = CountDownLatch(1)
            frame.core.sources.beforeFolderDeleted = {
                held.countDown()
                hold.await()
            }
            assertEquals(204, frame.send("DELETE", "/api/v1/sources/$source").statusCode())
            assertTrue(held.await(5, TimeUnit.SECONDS), "the deletion runs in the background")
            assertEquals(0, photos(frame), "the photos are gone at once")
            assertEquals(1000, originals(source).list()!!.size, "the originals wait for the hook")
            hold.countDown()
            waitFor(message = "the originals deleted") { !originals(source).exists() }
            frame.core.cache.awaitDeletions()
            assertEquals(emptyList<String>(), renders.list()!!.filter { it.startsWith("$source-") })
        }
    }

    @Test
    fun `A deletion cut off by the end of the app is completed at the next start`() {
        val (source, entries) = sourceWithManyPhotos()
        TestFrame(dir, nextcloudClients = clients).use { frame ->
            // The app ends before the background deletion ran.
            frame.core.sources.beforeFolderDeleted = { throw kotlinx.coroutines.CancellationException("The app ended") }
            assertEquals(204, frame.send("DELETE", "/api/v1/sources/$source").statusCode())
        }
        // What the delete thread of the renderings had not reached yet.
        entries.take(10).forEach { File(renders, "${it.id}-v1-400x300.jpg").writeBytes(ByteArray(10)) }
        assertTrue(originals(source).exists())
        TestFrame(dir, nextcloudClients = clients).use { frame ->
            frame.core.cache.awaitDeletions()
            assertTrue(!originals(source).exists(), "the originals folder of the removed source is gone")
            assertEquals(emptyList<String>(), renders.list()!!.filter { it.startsWith("$source-") })
        }
    }
}
