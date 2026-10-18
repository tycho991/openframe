package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.TestFrame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.imageio.ImageIO

/** Spec `photo-library` über die API, jede Antwort gegen den Vertrag geprüft. */
class LibraryApiTest {
    @TempDir
    lateinit var dir: File

    private val frame by lazy { TestFrame(dir) }

    @AfterEach
    fun tearDown() = frame.close()

    private fun page(query: String = "") = ApiJson.decodeFromString(PhotoPageDto.serializer(), frame.get("/api/v1/photos$query").body())

    private fun id(path: String) = PhotoEntry.idFor("local", path)

    private fun put(
        path: String,
        fixture: String = "landscape.jpg",
    ) = Fixtures.copy(fixture, File(frame.photos, path))

    @Test
    fun `Foto ausblenden und einblenden`() {
        put("a.jpg")
        frame.scan()
        val res = frame.send("PATCH", "/api/v1/photos/${id("a.jpg")}", """{"hidden":true}""")
        assertEquals(200, res.statusCode())
        val dto = ApiJson.decodeFromString(PhotoDto.serializer(), res.body())
        assertTrue(dto.hidden)
        assertFalse(dto.visible)
        assertEquals(
            emptyList<PhotoEntry>(),
            frame.core.visiblePhotos.value.also { Thread.sleep(50) }.filter {
                it.path == "a.jpg" &&
                    !it.hidden
            },
        )
        assertEquals(1, page().total, "bleibt in der Bibliothek")
        frame.send("PATCH", "/api/v1/photos/${id("a.jpg")}", """{"hidden":false}""")
        assertTrue(page().items.single().visible)
        assertEquals(404, frame.send("PATCH", "/api/v1/photos/local-${"0".repeat(40)}", """{"hidden":true}""").statusCode())
    }

    @Test
    fun `Ausgeblendetes Album bekommt neues Foto`() {
        put("urlaub/1.jpg")
        put("familie/2.jpg")
        frame.scan()
        val res = frame.send("PATCH", "/api/v1/albums/urlaub", """{"hidden":true}""")
        assertEquals(200, res.statusCode())
        assertTrue(ApiJson.decodeFromString(AlbumDto.serializer(), res.body()).hidden)
        put("urlaub/neu.jpg")
        frame.scan()
        val urlaub = page("?album=urlaub").items
        assertEquals(2, urlaub.size)
        assertTrue(urlaub.none { it.visible }, "auch das neue Foto ist ausgeblendet")
        assertTrue(urlaub.none { it.hidden }, "ausgeblendet über das Album, nicht einzeln")
        assertEquals(404, frame.send("PATCH", "/api/v1/albums/gibtsnicht", """{"hidden":true}""").statusCode())
    }

    @Test
    fun `Alben mit Anzahl und Titelbild`() {
        put("urlaub/1.jpg")
        put("urlaub/2.jpg", "rotated6.jpg")
        put("lose.jpg")
        frame.scan()
        val albums = ApiJson.decodeFromString(AlbumListDto.serializer(), frame.get("/api/v1/albums").body()).albums
        assertEquals(listOf("urlaub"), albums.map { it.name })
        assertEquals(2, albums.single().count)
        // Neuestes Foto: landscape.jpg vom 14.07.2024 gegenüber rotated6.jpg vom 02.01.2023.
        assertTrue(
            albums
                .single()
                .cover!!
                .thumb
                .contains(id("urlaub/1.jpg")),
        )
    }

    @Test
    fun `Mehrere ausblenden und loeschen per Batch`() {
        listOf("a.jpg", "b.jpg", "c.jpg").forEach { put(it) }
        frame.scan()
        val ids = listOf("a.jpg", "b.jpg").map(::id)
        val res =
            frame.send(
                "POST",
                "/api/v1/photos:batch",
                """{"action":"hide","ids":["${ids[0]}","${ids[1]}","local-${"f".repeat(40)}"]}""",
            )
        assertEquals(200, res.statusCode())
        val result = ApiJson.decodeFromString(BatchResultDto.serializer(), res.body())
        assertEquals(2, result.processed)
        assertEquals(listOf("local-${"f".repeat(40)}"), result.missing)
        assertEquals(1, page("?visible=true").total)
        frame.send("POST", "/api/v1/photos:batch", """{"action":"delete","ids":["${ids[0]}"]}""")
        assertFalse(File(frame.photos, "a.jpg").exists())
        assertEquals(400, frame.send("POST", "/api/v1/photos:batch", """{"action":"drehen","ids":["${ids[1]}"]}""").statusCode())
    }

    @Test
    fun `Seite abrufen`() {
        for (i in 1..250) put("urlaub/%03d.jpg".format(i))
        for (i in 1..20) put("anderes/%03d.jpg".format(i))
        frame.scan()
        val first = page("?album=urlaub&limit=100&sort=name_asc")
        assertEquals(250, first.total)
        val second = page("?album=urlaub&limit=100&sort=name_asc&cursor=${first.nextCursor}")
        assertEquals(100, second.items.size)
        assertTrue(second.items.all { it.album == "urlaub" })
        assertEquals("urlaub/101.jpg", second.items.first().path)
        assertNotNull(second.nextCursor)
        val third = page("?album=urlaub&limit=100&sort=name_asc&cursor=${second.nextCursor}")
        assertEquals(50, third.items.size)
        assertNull(third.nextCursor)
        // Ändert sich die Bibliothek zwischen zwei Seiten, bleibt der Cursor gültig.
        File(frame.photos, "urlaub/101.jpg").delete()
        frame.scan()
        assertEquals("urlaub/102.jpg", page("?album=urlaub&limit=100&sort=name_asc&cursor=${first.nextCursor}").items.first().path)
        assertEquals(400, frame.get("/api/v1/photos?cursor=kaputt").statusCode())
        assertEquals(400, frame.get("/api/v1/photos?limit=0").statusCode())
        assertEquals(400, frame.get("/api/v1/photos?sort=zufall").statusCode())
    }

    @Test
    fun `Sortierung nach Datum`() {
        put("alt.jpg", "rotated6.jpg")
        put("neu.jpg", "landscape.jpg")
        frame.scan()
        assertEquals(listOf("neu.jpg", "alt.jpg"), page().items.map { it.path })
        assertEquals(listOf("alt.jpg", "neu.jpg"), page("?sort=date_asc").items.map { it.path })
    }

    @Test
    fun `Eintraege tragen signierte Bild-URLs`() {
        put("a.jpg")
        frame.scan()
        val photo = page().items.single()
        assertTrue(photo.urls.thumb.contains("size=thumb"))
        assertTrue(photo.urls.display.contains("t="))
        // Ohne Token, nur mit der Signatur.
        val res = frame.http.requestBytes("GET", photo.urls.thumb)
        assertEquals(200, res.statusCode())
        assertEquals("image/jpeg", res.header("content-type"))
    }

    @Test
    fun `Vorschaubild abrufen und wiederholter Abruf`() {
        put("a.jpg")
        frame.scan()
        val url =
            page()
                .items
                .single()
                .urls.thumb
        val res = frame.http.requestBytes("GET", url)
        assertEquals(200, res.statusCode())
        assertTrue(res.header("cache-control")!!.contains("immutable"))
        val img = ImageIO.read(res.bytes().inputStream())
        assertEquals(400, maxOf(img.width, img.height))
        val renders = frame.engine.renders.get()
        val again = frame.http.requestBytes("GET", url, headers = mapOf("If-None-Match" to res.header("etag")!!))
        assertEquals(304, again.statusCode())
        assertEquals(renders, frame.engine.renders.get(), "ohne neu zu rendern")
    }

    @Test
    fun `Anzeigegroesse in Bildschirmaufloesung`() {
        put("a.jpg")
        frame.scan()
        val res =
            frame.http.requestBytes(
                "GET",
                page()
                    .items
                    .single()
                    .urls.display,
            )
        val img = ImageIO.read(res.bytes().inputStream())
        // 400×300 auf 1024×600 bedeckt nur hochskaliert, deshalb bleibt es bei der Originalgröße.
        assertEquals(400 to 300, img.width to img.height)
    }

    @Test
    fun `Defektes Bild liefert 503`() {
        put("a.jpg")
        frame.scan()
        Fixtures.copy("broken.jpg", File(frame.photos, "a.jpg")).setLastModified(File(frame.photos, "a.jpg").lastModified())
        val id = id("a.jpg")
        assertEquals(503, frame.get("/api/v1/photos/$id/image?size=display").statusCode())
        assertEquals(400, frame.get("/api/v1/photos/$id/image?size=riesig").statusCode())
    }

    @Test
    fun `Foto loeschen`() {
        put("urlaub/a.jpg")
        frame.scan()
        val id = id("urlaub/a.jpg")
        frame.get("/api/v1/photos/$id/image?size=thumb")
        assertTrue(File(frame.dirs.cache, "renders").list()!!.isNotEmpty())
        assertEquals(204, frame.send("DELETE", "/api/v1/photos/$id").statusCode())
        assertFalse(File(frame.photos, "urlaub/a.jpg").exists())
        assertEquals(0, page().total)
        assertEquals(0, File(frame.dirs.cache, "renders").list()!!.size, "gerenderte Größen entfernt")
        assertTrue(frame.notified.any { it.name == "a.jpg" }, "dem MediaStore gemeldet")
        assertEquals(404, frame.send("DELETE", "/api/v1/photos/$id").statusCode())
    }

    @Test
    fun `Uebersprungene Dateien`() {
        put("urlaub/x.heic", "photo.heic")
        put("kaputt.jpg", "broken.jpg")
        frame.scan()
        val items = ApiJson.decodeFromString(SkippedFileListDto.serializer(), frame.get("/api/v1/skipped-files").body()).items
        assertEquals(mapOf("kaputt.jpg" to "corrupt", "urlaub/x.heic" to "unsupported"), items.associate { it.path to it.reason })
    }

    @Test
    fun `Neues Foto aendert die Bibliotheksversion`() {
        val before =
            Json
                .parseToJsonElement(frame.get("/api/v1/state").body())
                .jsonObject["libraryVersion"]!!
                .jsonPrimitive.content
        put("a.jpg")
        frame.scan()
        val after =
            Json
                .parseToJsonElement(frame.get("/api/v1/state").body())
                .jsonObject["libraryVersion"]!!
                .jsonPrimitive.content
        assertNotEquals(before, after)
    }

    @Test
    fun `Foto per USB kopiert erscheint ohne manuellen Scan`() {
        put("usb.jpg")
        val deadline = System.currentTimeMillis() + 5000
        while (frame.core.visiblePhotos.value
                .isEmpty() && System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(50)
        }
        assertEquals(
            listOf("usb.jpg"),
            frame.core.visiblePhotos.value
                .map { it.path },
        )
    }
}
