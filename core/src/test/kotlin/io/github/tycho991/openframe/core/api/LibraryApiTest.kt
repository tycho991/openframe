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
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.imageio.ImageIO

/** Spec `photo-library` through the API, every response checked against the contract. */
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
    fun `Hide and unhide photo`() {
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
        assertEquals(1, page().total, "stays in the library")
        frame.send("PATCH", "/api/v1/photos/${id("a.jpg")}", """{"hidden":false}""")
        assertTrue(page().items.single().visible)
        assertEquals(404, frame.send("PATCH", "/api/v1/photos/local-${"0".repeat(40)}", """{"hidden":true}""").statusCode())
    }

    @Test
    fun `Hidden album receives a new photo`() {
        put("holiday/1.jpg")
        put("family/2.jpg")
        frame.scan()
        val res = frame.send("PATCH", "/api/v1/albums/holiday", """{"hidden":true}""")
        assertEquals(200, res.statusCode())
        assertTrue(ApiJson.decodeFromString(AlbumDto.serializer(), res.body()).hidden)
        put("holiday/new.jpg")
        frame.scan()
        val holiday = page("?album=holiday").items
        assertEquals(2, holiday.size)
        assertTrue(holiday.none { it.visible }, "the new photo is hidden as well")
        assertTrue(holiday.none { it.hidden }, "hidden through the album, not individually")
        assertEquals(404, frame.send("PATCH", "/api/v1/albums/doesnotexist", """{"hidden":true}""").statusCode())
    }

    @Test
    fun `Albums with count and cover image`() {
        put("holiday/1.jpg")
        put("holiday/2.jpg", "rotated6.jpg")
        put("loose.jpg")
        frame.scan()
        val albums = ApiJson.decodeFromString(AlbumListDto.serializer(), frame.get("/api/v1/albums").body()).albums
        assertEquals(listOf("holiday"), albums.map { it.name })
        assertEquals(2, albums.single().count)
        // Newest photo: landscape.jpg of 14 Jul 2024 versus rotated6.jpg of 2 Jan 2023.
        assertTrue(
            albums
                .single()
                .cover!!
                .thumb
                .contains(id("holiday/1.jpg")),
        )
    }

    @Test
    fun `Batch delete goes on after a photo that cannot be deleted`() {
        put("locked/a.jpg")
        put("b.jpg")
        frame.scan()
        val folder = File(frame.photos, "locked")
        // A folder without write permission: its file cannot be deleted. Not enforced for root.
        folder.setWritable(false)
        try {
            assumeTrue(!folder.canWrite(), "folder stays writable (running as root)")
            val res =
                frame.send(
                    "POST",
                    "/api/v1/photos:batch",
                    """{"action":"delete","ids":["${id("locked/a.jpg")}","${id("b.jpg")}"]}""",
                )
            assertEquals(200, res.statusCode())
            val result = ApiJson.decodeFromString(BatchResultDto.serializer(), res.body())
            assertEquals(1, result.processed)
            assertEquals(listOf(id("locked/a.jpg")), result.failed)
            assertEquals(emptyList<String>(), result.missing)
            assertFalse(File(frame.photos, "b.jpg").exists())
        } finally {
            folder.setWritable(true)
        }
    }

    /** A photo as the Nextcloud source would have put it into the index: no file in the photo folder. */
    private fun putRemote(path: String = "holiday/r.jpg"): String {
        val entry = PhotoEntry(PhotoEntry.idFor("nextcloud", path), "nextcloud", path, 400, 300, 0, 1, 1, 1, etag = "e1")
        frame.core.library.index
            .edit { it.put(entry) }
        return entry.id
    }

    @Test
    fun `The photo list names the source`() {
        put("a.jpg")
        frame.scan()
        putRemote()
        val sources = page().items.associate { it.path to it.source }
        assertEquals(mapOf("a.jpg" to "local", "holiday/r.jpg" to "nextcloud"), sources)
    }

    @Test
    fun `A Nextcloud photo cannot be deleted but can be hidden`() {
        val id = putRemote()
        val res = frame.send("DELETE", "/api/v1/photos/$id")
        assertEquals(409, res.statusCode())
        assertEquals(
            "read_only_source",
            Json
                .parseToJsonElement(res.body())
                .jsonObject["error"]!!
                .jsonObject["code"]!!
                .jsonPrimitive.content,
        )
        assertEquals(1, page().total, "the photo stays")
        val hidden = frame.send("PATCH", "/api/v1/photos/$id", """{"hidden":true}""")
        assertEquals(200, hidden.statusCode())
        assertEquals("nextcloud", ApiJson.decodeFromString(PhotoDto.serializer(), hidden.body()).source)
    }

    @Test
    fun `A batch that deletes a local and a Nextcloud photo reports the Nextcloud one as failed`() {
        put("a.jpg")
        frame.scan()
        val remote = putRemote()
        val res = frame.send("POST", "/api/v1/photos:batch", """{"action":"delete","ids":["${id("a.jpg")}","$remote"]}""")
        assertEquals(200, res.statusCode())
        val result = ApiJson.decodeFromString(BatchResultDto.serializer(), res.body())
        assertEquals(1, result.processed)
        assertEquals(listOf(remote), result.failed)
        assertFalse(File(frame.photos, "a.jpg").exists())
        assertEquals(listOf("holiday/r.jpg"), page().items.map { it.path })
    }

    @Test
    fun `Hide and delete several by batch`() {
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
        assertEquals(emptyList<String>(), result.failed)
        assertEquals(1, page("?visible=true").total)
        frame.send("POST", "/api/v1/photos:batch", """{"action":"delete","ids":["${ids[0]}"]}""")
        assertFalse(File(frame.photos, "a.jpg").exists())
        assertEquals(400, frame.send("POST", "/api/v1/photos:batch", """{"action":"rotate","ids":["${ids[1]}"]}""").statusCode())
    }

    @Test
    fun `Fetch page`() {
        for (i in 1..250) put("holiday/%03d.jpg".format(i))
        for (i in 1..20) put("other/%03d.jpg".format(i))
        frame.scan()
        val first = page("?album=holiday&limit=100&sort=name_asc")
        assertEquals(250, first.total)
        val second = page("?album=holiday&limit=100&sort=name_asc&cursor=${first.nextCursor}")
        assertEquals(100, second.items.size)
        assertTrue(second.items.all { it.album == "holiday" })
        assertEquals("holiday/101.jpg", second.items.first().path)
        assertNotNull(second.nextCursor)
        val third = page("?album=holiday&limit=100&sort=name_asc&cursor=${second.nextCursor}")
        assertEquals(50, third.items.size)
        assertNull(third.nextCursor)
        // If the library changes between two pages, the cursor stays valid.
        File(frame.photos, "holiday/101.jpg").delete()
        frame.scan()
        assertEquals("holiday/102.jpg", page("?album=holiday&limit=100&sort=name_asc&cursor=${first.nextCursor}").items.first().path)
        assertEquals(400, frame.get("/api/v1/photos?cursor=broken").statusCode())
        assertEquals(400, frame.get("/api/v1/photos?limit=0").statusCode())
        assertEquals(400, frame.get("/api/v1/photos?sort=random").statusCode())
    }

    @Test
    fun `Sorting by date`() {
        put("old.jpg", "rotated6.jpg")
        put("new.jpg", "landscape.jpg")
        frame.scan()
        assertEquals(listOf("new.jpg", "old.jpg"), page().items.map { it.path })
        assertEquals(listOf("old.jpg", "new.jpg"), page("?sort=date_asc").items.map { it.path })
    }

    @Test
    fun `Entries carry signed image URLs`() {
        put("a.jpg")
        frame.scan()
        val photo = page().items.single()
        assertTrue(photo.urls.thumb.contains("size=thumb"))
        assertTrue(photo.urls.display.contains("t="))
        // Without a token, only with the signature.
        val res = frame.http.requestBytes("GET", photo.urls.thumb)
        assertEquals(200, res.statusCode())
        assertEquals("image/jpeg", res.header("content-type"))
    }

    @Test
    fun `Fetch thumbnail and repeated request`() {
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
        assertEquals(renders, frame.engine.renders.get(), "without rendering again")
    }

    @Test
    fun `Display size at screen resolution`() {
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
        // 400×300 on 1024×600 would cover only when scaled up, so it stays at the original size.
        assertEquals(400 to 300, img.width to img.height)
    }

    @Test
    fun `Broken image yields 503`() {
        // In the index but not renderable, for example after damage on the storage.
        val file = put("a.jpg", "broken.jpg")
        val id = id("a.jpg")
        frame.core.library.index.edit {
            it.put(PhotoEntry(id, "local", "a.jpg", 400, 300, 0, file.length(), file.lastModified(), 1))
        }
        assertEquals(503, frame.get("/api/v1/photos/$id/image?size=display").statusCode())
        assertEquals(400, frame.get("/api/v1/photos/$id/image?size=huge").statusCode())
    }

    @Test
    fun `Delete photo`() {
        put("holiday/a.jpg")
        frame.scan()
        val id = id("holiday/a.jpg")
        frame.get("/api/v1/photos/$id/image?size=thumb")
        assertTrue(File(frame.dirs.cache, "renders").list()!!.isNotEmpty())
        assertEquals(204, frame.send("DELETE", "/api/v1/photos/$id").statusCode())
        assertFalse(File(frame.photos, "holiday/a.jpg").exists())
        assertEquals(0, page().total)
        assertEquals(0, File(frame.dirs.cache, "renders").list()!!.size, "rendered sizes removed")
        assertTrue(frame.notified.any { it.name == "a.jpg" }, "reported to the MediaStore")
        assertEquals(404, frame.send("DELETE", "/api/v1/photos/$id").statusCode())
    }

    @Test
    fun `Skipped files`() {
        put("holiday/x.heic", "photo.heic")
        put("damaged.jpg", "broken.jpg")
        frame.scan()
        val items = ApiJson.decodeFromString(SkippedFileListDto.serializer(), frame.get("/api/v1/skipped-files").body()).items
        assertEquals(mapOf("damaged.jpg" to "corrupt", "holiday/x.heic" to "unsupported"), items.associate { it.path to it.reason })
    }

    @Test
    fun `New photo changes the library version`() {
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
    fun `Photo copied over USB appears without a manual scan`() {
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
