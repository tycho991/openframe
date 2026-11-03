package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.platform.DecoderUnavailableException
import io.github.tycho991.openframe.core.platform.ImageException
import io.github.tycho991.openframe.core.platform.SkipReason
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.TestFrame
import io.github.tycho991.openframe.core.testing.waitFor
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
 * Spec `photo-library`: Skipped after a decoder crash, Quarantined photos; `kiosk-host`: Decoding in a separate
 * process › Decoder cannot be started. Through the API with the contract check, with an engine that crashes on one file.
 */
class QuarantineTest {
    @TempDir
    lateinit var dir: File

    private val frame by lazy { TestFrame(dir) }

    @AfterEach
    fun tearDown() = frame.close()

    private fun id(path: String) = PhotoEntry.idFor("local", path)

    private fun put(path: String) = Fixtures.copy("landscape.jpg", File(frame.photos, path))

    private fun crash() = ImageException(SkipReason.CRASHED, "The decoder died")

    private fun photo(path: String) =
        ApiJson.decodeFromString(PhotoPageDto.serializer(), frame.get("/api/v1/photos").body()).items.single { it.path == path }

    private fun skipped() =
        ApiJson
            .decodeFromString(SkippedFileListDto.serializer(), frame.get("/api/v1/skipped-files").body())
            .items
            .associate { it.path to it.reason }

    private fun errorCode(res: io.github.tycho991.openframe.core.testing.Res) =
        Json
            .parseToJsonElement(res.body())
            .jsonObject["error"]!!
            .jsonObject["code"]!!
            .jsonPrimitive.content

    @Test
    fun `A file that crashes the decoder while it is checked is skipped as crashed and not checked again`() {
        put("ok.jpg")
        put("poison.jpg")
        frame.engine.probeFailure = { if (it.name == "poison.jpg") crash() else null }
        frame.scan()
        assertEquals(mapOf("poison.jpg" to "crashed"), skipped())
        val probes = frame.engine.probes.get()
        frame.scan()
        assertEquals(probes, frame.engine.probes.get(), "not probed again")

        // An upload that crashes the decoder is refused like an unsupported format, and the log names the file.
        frame.engine.probeFailure = { crash() }
        val upload = frame.upload(null, "bad.jpg" to Fixtures.file("landscape.jpg").readBytes())
        assertEquals(415, upload.statusCode())
        assertEquals("unsupported_format", errorCode(upload))
        assertTrue(frame.core.log.recent(1000).any { "bad.jpg" in it.message && "crash" in it.message })

        // Replaced by a file of a different size: checked again.
        frame.engine.probeFailure = { null }
        Fixtures.copy("gps-siena.jpg", File(frame.photos, "poison.jpg"))
        frame.scan()
        assertEquals(emptyMap<String, String>(), skipped())
        assertEquals("poison.jpg", photo("poison.jpg").path)
    }

    @Test
    fun `A photo that crashes the decoder while it renders is quarantined and keeps everything else`() {
        put("a.jpg")
        put("b.jpg")
        frame.scan()
        frame.send("PATCH", "/api/v1/photos/${id("a.jpg")}", """{"comment":"Garden"}""")
        frame.engine.renderFailure = { if (it.name == "a.jpg") crash() else null }

        assertEquals(503, frame.get("/api/v1/photos/${id("a.jpg")}/image?size=full").statusCode())
        waitFor(message = "quarantined") { photo("a.jpg").quarantined }
        val dto = photo("a.jpg")
        assertTrue(dto.visible, "visibility unchanged")
        assertEquals("Garden", dto.comment)
        waitFor(message = "out of the slideshow") {
            frame.core.visiblePhotos.value
                .none { it.path == "a.jpg" }
        }

        // No image of any size and no rendering attempt any more.
        val renders = frame.engine.renders.get()
        for (size in listOf("thumb", "display", "full")) {
            val res = frame.get("/api/v1/photos/${id("a.jpg")}/image?size=$size")
            assertEquals(503, res.statusCode())
            assertEquals("render_failed", errorCode(res))
        }
        assertEquals(renders, frame.engine.renders.get(), "the engine is not called")

        // Hiding works, and trying it again renders it again.
        assertTrue(photoOf(frame.send("PATCH", "/api/v1/photos/${id("a.jpg")}", """{"hidden":true}""")).quarantined)
        frame.engine.renderFailure = { null }
        val retried = photoOf(frame.send("PATCH", "/api/v1/photos/${id("a.jpg")}", """{"quarantined":false}"""))
        assertFalse(retried.quarantined)
        assertEquals(200, frame.get("/api/v1/photos/${id("a.jpg")}/image?size=full").statusCode())
        assertEquals(400, frame.send("PATCH", "/api/v1/photos/${id("a.jpg")}", """{"quarantined":true}""").statusCode())
    }

    private fun photoOf(res: io.github.tycho991.openframe.core.testing.Res) = ApiJson.decodeFromString(PhotoDto.serializer(), res.body())

    @Test
    fun `A quarantined photo crashes again on retry, can be deleted, and a new version lifts the quarantine`() {
        put("a.jpg")
        put("b.jpg")
        frame.scan()
        frame.engine.renderFailure = { if (it.name == "a.jpg" || it.name == "b.jpg") crash() else null }
        frame.get("/api/v1/photos/${id("a.jpg")}/image?size=full")
        frame.get("/api/v1/photos/${id("b.jpg")}/image?size=full")
        waitFor { photo("a.jpg").quarantined && photo("b.jpg").quarantined }

        // Retry: quarantined again if the decoder crashes again.
        frame.send("PATCH", "/api/v1/photos/${id("a.jpg")}", """{"quarantined":false}""")
        assertEquals(503, frame.get("/api/v1/photos/${id("a.jpg")}/image?size=full").statusCode())
        waitFor { photo("a.jpg").quarantined }

        // A new version of the file.
        frame.engine.renderFailure = { null }
        Fixtures.copy("gps-siena.jpg", File(frame.photos, "a.jpg"))
        frame.scan()
        assertFalse(photo("a.jpg").quarantined)
        assertEquals(200, frame.get("/api/v1/photos/${id("a.jpg")}/image?size=full").statusCode())

        // Delete a quarantined local photo.
        assertEquals(204, frame.send("DELETE", "/api/v1/photos/${id("b.jpg")}").statusCode())
        assertFalse(File(frame.photos, "b.jpg").exists())
    }

    @Test
    fun `An album whose only photo is quarantined has no cover`() {
        put("holiday/a.jpg")
        put("home/b.jpg")
        frame.scan()
        frame.engine.renderFailure = { if (it.name == "a.jpg") crash() else null }
        frame.get("/api/v1/photos/${PhotoEntry.idFor("local", "holiday/a.jpg")}/image?size=thumb")
        waitFor { photo("holiday/a.jpg").quarantined }
        val albums =
            ApiJson
                .decodeFromString(AlbumListDto.serializer(), frame.get("/api/v1/albums").body())
                .albums
                .associateBy { it.name }
        assertNull(albums.getValue("holiday").cover)
        assertTrue(albums.getValue("home").cover != null)
    }

    @Test
    fun `A decoder that cannot be started records nothing, and the files are checked later`() {
        put("a.jpg")
        put("b.jpg")
        frame.engine.probeFailure = { DecoderUnavailableException("not bound") }
        frame.scan()
        assertEquals(emptyMap<String, String>(), skipped())
        assertEquals(
            emptyList<String>(),
            frame.core.library.index.library.value.photos.values
                .map { it.path },
        )

        val upload = frame.upload(null, "c.jpg" to Fixtures.file("landscape.jpg").readBytes())
        assertEquals(503, upload.statusCode())
        assertEquals("decoder_unavailable", errorCode(upload))

        frame.engine.probeFailure = { null }
        frame.scan()
        assertEquals(
            setOf("a.jpg", "b.jpg"),
            frame.core.library.index.library.value.photos.values
                .map { it.path }
                .toSet(),
        )
    }
}
