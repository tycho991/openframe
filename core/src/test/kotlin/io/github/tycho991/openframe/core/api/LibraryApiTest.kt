package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.library.Crop
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.TestFrame
import io.github.tycho991.openframe.core.testing.waitFor
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
        // The slideshow's list follows the library in the background: first it has the photo, then it loses it.
        waitFor(message = "a.jpg in the slideshow") {
            frame.core.visiblePhotos.value
                .any { it.path == "a.jpg" }
        }
        val res = frame.send("PATCH", "/api/v1/photos/${id("a.jpg")}", """{"hidden":true}""")
        assertEquals(200, res.statusCode())
        val dto = ApiJson.decodeFromString(PhotoDto.serializer(), res.body())
        assertTrue(dto.hidden)
        assertFalse(dto.visible)
        waitFor(message = "a.jpg out of the slideshow") {
            frame.core.visiblePhotos.value
                .none { it.path == "a.jpg" }
        }
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
        val res = patchAlbum("holiday", true)
        assertEquals(200, res.statusCode())
        assertTrue(albumsOf(res).getValue("holiday").hidden)
        put("holiday/new.jpg")
        frame.scan()
        val holiday = page("?album=holiday").items
        assertEquals(2, holiday.size)
        assertTrue(holiday.none { it.visible }, "the new photo is hidden as well")
        assertTrue(holiday.none { it.hidden }, "hidden through the album, not individually")
    }

    private fun patchAlbum(
        name: String,
        hidden: Boolean,
    ) = frame.send("PATCH", "/api/v1/albums", """{"name":"$name","hidden":$hidden}""")

    private fun albumsOf(res: io.github.tycho991.openframe.core.testing.Res) =
        ApiJson.decodeFromString(AlbumListDto.serializer(), res.body()).albums.associateBy { it.name }

    private fun albums() = albumsOf(frame.get("/api/v1/albums"))

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
    fun `Album list with sub-albums, counted and covered by branch`() {
        put("holiday/2023/a.jpg", "rotated6.jpg")
        put("holiday/2024/may/b.jpg")
        put("family/c.jpg")
        frame.scan()
        val res = frame.get("/api/v1/albums")
        assertEquals(
            listOf("family", "holiday", "holiday/2023", "holiday/2024", "holiday/2024/may"),
            ApiJson.decodeFromString(AlbumListDto.serializer(), res.body()).albums.map { it.name },
        )
        val holiday = albumsOf(res).getValue("holiday")
        assertEquals(2, holiday.count)
        assertTrue(holiday.cover!!.thumb.contains(id("holiday/2024/may/b.jpg")), "newest photo of the branch")
        assertTrue(albumsOf(res).values.none { it.hidden || it.overridesParent })
    }

    @Test
    fun `Switching a parent with an exception below resets it`() {
        put("holiday/h.jpg")
        put("holiday/2024/a.jpg")
        frame.scan()
        patchAlbum("holiday", true)
        val exception = albumsOf(patchAlbum("holiday/2024", false))
        assertFalse(exception.getValue("holiday/2024").hidden)
        assertTrue(exception.getValue("holiday/2024").overridesParent)
        assertTrue(page().items.single { it.path == "holiday/2024/a.jpg" }.visible, "shown inside a hidden album")
        assertFalse(page().items.single { it.path == "holiday/h.jpg" }.visible)
        patchAlbum("holiday", false)
        val after = albumsOf(patchAlbum("holiday", true))
        assertTrue(after.getValue("holiday/2024").hidden)
        assertFalse(after.getValue("holiday/2024").overridesParent, "the answer carries the reset sub-album")
        assertEquals(after, albums())
    }

    @Test
    fun `Switching a sub-album back to its parent's state`() {
        put("holiday/2024/a.jpg")
        frame.scan()
        patchAlbum("holiday", true)
        patchAlbum("holiday/2024", false)
        val back = albumsOf(patchAlbum("holiday/2024", true)).getValue("holiday/2024")
        assertTrue(back.hidden)
        assertFalse(back.overridesParent)
    }

    @Test
    fun `Unknown album`() {
        put("holiday/2024/a.jpg")
        frame.scan()
        assertEquals(404, patchAlbum("doesnotexist", true).statusCode())
        assertEquals(404, patchAlbum("holiday/2025", true).statusCode())
        assertEquals(404, patchAlbum("holi", true).statusCode(), "a prefix of a name is not an album")
        assertEquals(200, patchAlbum("holiday", true).statusCode(), "an album without photos of its own")
        assertEquals(400, frame.send("PATCH", "/api/v1/albums", """{"hidden":true}""").statusCode())
    }

    @Test
    fun `Filter by an album with and without its sub-albums`() {
        put("holiday/a.jpg")
        put("holiday/2024/b.jpg")
        put("holiday/2024/may/c.jpg")
        put("holidays/d.jpg")
        frame.scan()
        assertEquals(
            setOf("holiday/a.jpg", "holiday/2024/b.jpg", "holiday/2024/may/c.jpg"),
            page("?album=holiday").items.map { it.path }.toSet(),
        )
        assertEquals(listOf("holiday/a.jpg"), page("?album=holiday&subalbums=false").items.map { it.path })
        assertEquals(setOf("holiday/2024/b.jpg", "holiday/2024/may/c.jpg"), page("?album=holiday/2024").items.map { it.path }.toSet())
        assertEquals("holiday/2024/may", page("?album=holiday/2024/may").items.single().album)
        assertEquals(400, frame.get("/api/v1/photos?album=holiday&subalbums=maybe").statusCode())
    }

    @Test
    fun `A photo in a shown sub-album of a hidden album is visible`() {
        put("holiday/a.jpg")
        put("holiday/2024/b.jpg")
        frame.scan()
        patchAlbum("holiday", true)
        patchAlbum("holiday/2024", false)
        assertEquals(listOf("holiday/2024/b.jpg"), page("?visible=true").items.map { it.path })
        assertEquals(listOf("holiday/a.jpg"), page("?visible=false").items.map { it.path })
        val deadline = System.currentTimeMillis() + 5000
        while (frame.core.visiblePhotos.value
                .map { it.path } != listOf("holiday/2024/b.jpg") && System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20)
        }
        assertEquals(
            listOf("holiday/2024/b.jpg"),
            frame.core.visiblePhotos.value
                .map { it.path },
            "the slideshow follows",
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

    private fun patch(
        id: String,
        body: String,
    ) = frame.send("PATCH", "/api/v1/photos/$id", body)

    private fun photoOf(res: io.github.tycho991.openframe.core.testing.Res) = ApiJson.decodeFromString(PhotoDto.serializer(), res.body())

    private fun errorFields(res: io.github.tycho991.openframe.core.testing.Res) =
        Json
            .parseToJsonElement(res.body())
            .jsonObject["error"]!!
            .jsonObject["fields"]
            ?.jsonObject
            ?.keys
            .orEmpty()

    @Test
    fun `Set a section`() {
        put("a.jpg")
        frame.scan()
        val res = patch(id("a.jpg"), """{"crop":{"x":0.5,"y":0.2,"zoom":1.5}}""")
        assertEquals(200, res.statusCode(), res.body())
        val dto = photoOf(res)
        assertEquals(Crop(0.5, 0.2, 1.5), dto.crop)
        assertEquals(
            Crop(0.5, 0.2, 1.5),
            frame.core.library.index.library.value.photos[id("a.jpg")]!!
                .crop,
        )
        // 400×300 on 1024×600 cannot be zoomed beyond 1: the window is the one of `fill`, moved to the top.
        assertEquals(1.0, dto.presentation.zoom, 1e-9)
        assertEquals(0.0, dto.presentation.y, 1e-9)
        assertFalse(dto.hidden, "a patch of only the section leaves the hidden state")
    }

    @Test
    fun `Invalid focus point rejects the whole patch`() {
        put("a.jpg")
        frame.scan()
        patch(id("a.jpg"), """{"crop":{"x":0.5,"y":0.2,"zoom":1}}""")
        val res = patch(id("a.jpg"), """{"hidden":true,"crop":{"x":1.2,"y":0.5,"zoom":1}}""")
        assertEquals(400, res.statusCode())
        assertEquals(setOf("crop.x"), errorFields(res))
        val photo = page().items.single()
        assertEquals(Crop(0.5, 0.2, 1.0), photo.crop, "the previous section stays")
        assertFalse(photo.hidden, "and the hidden state as well")
        for ((body, field) in listOf(
            """{"crop":{"x":0.5,"y":-0.1,"zoom":1}}""" to "crop.y",
            """{"crop":{"x":0.5,"y":0.5,"zoom":0}}""" to "crop.zoom",
            """{"crop":{"x":0.5,"y":0.5,"zoom":4.5}}""" to "crop.zoom",
            """{"crop":{"x":0.5,"zoom":1}}""" to "crop.y",
            """{"crop":{"x":"0.5","y":0.5,"zoom":1}}""" to "crop.x",
            """{"crop":{"x":0.5,"y":0.5,"zoom":1,"w":1}}""" to "crop.w",
            """{"crop":true}""" to "crop",
            """{"hidden":"yes"}""" to "hidden",
            """{"rotation":90}""" to "rotation",
        )) {
            val r = patch(id("a.jpg"), body)
            assertEquals(400, r.statusCode(), body)
            assertEquals(setOf(field), errorFields(r), body)
        }
        assertEquals(400, patch(id("a.jpg"), "{}").statusCode(), "an empty patch")
        assertEquals(200, patch(id("a.jpg"), """{"crop":{"x":0,"y":1,"zoom":4}}""").statusCode(), "the limits are valid")
    }

    @Test
    fun `Remove a section and patch only hidden as before`() {
        put("a.jpg")
        frame.scan()
        frame.send("PATCH", "/api/v1/settings", """{"fillMode":"fit"}""")
        patch(id("a.jpg"), """{"crop":{"x":0,"y":0,"zoom":1}}""")
        val hidden = photoOf(patch(id("a.jpg"), """{"hidden":true}"""))
        assertTrue(hidden.hidden)
        assertEquals(Crop(0.0, 0.0, 1.0), hidden.crop, "hiding keeps the section")
        val removed = photoOf(patch(id("a.jpg"), """{"crop":null}"""))
        assertNull(removed.crop)
        assertTrue(removed.hidden)
        // The fill mode applies again: `fit` shows the whole photo.
        assertEquals(PresentationDto(0.0, 0.0, 1.0, 1.0, removed.presentation.zoom), removed.presentation)
    }

    @Test
    fun `Photo without a section`() {
        put("a.jpg")
        frame.scan()
        frame.send("PATCH", "/api/v1/settings", """{"fillMode":"fill"}""")
        val photo = page().items.single()
        assertNull(photo.crop)
        // 400×300 on 1024×600 in `fill`: the whole width and the middle 0.78125 of the height.
        assertEquals(1.0, photo.presentation.zoom, 1e-9)
        assertEquals(1.0, photo.presentation.width, 1e-9)
        assertEquals(0.78125, photo.presentation.height, 1e-9)
        assertEquals(0.109375, photo.presentation.y, 1e-9)
    }

    @Test
    fun `New section, new image`() {
        put("a.jpg")
        frame.scan()
        val before = page().items.single()
        val first = frame.http.requestBytes("GET", before.urls.display)
        assertEquals(200, first.statusCode())
        assertTrue(first.header("cache-control")!!.contains("immutable"))
        patch(id("a.jpg"), """{"crop":{"x":0.5,"y":0.5,"zoom":0.5}}""")
        val after = page().items.single()
        assertNotEquals(before.urls.display, after.urls.display)
        val second = frame.http.requestBytes("GET", after.urls.display)
        assertTrue(second.header("cache-control")!!.contains("immutable"))
        assertNotEquals(first.header("etag"), second.header("etag"))
        val img = ImageIO.read(second.bytes().inputStream())
        // Zoomed out to the whole photo: 400×300 without borders in the image.
        assertEquals(400 to 300, img.width to img.height)
        // The old URL now gets the new part and must not be cached for long.
        val old = frame.http.requestBytes("GET", before.urls.display)
        assertEquals(second.header("etag"), old.header("etag"))
        assertFalse(old.header("cache-control")!!.contains("immutable"))
    }

    @Test
    fun `Full size is the uncropped photo`() {
        put("a.jpg")
        frame.scan()
        patch(id("a.jpg"), """{"crop":{"x":0,"y":0,"zoom":1}}""")
        val res =
            frame.http.requestBytes(
                "GET",
                page()
                    .items
                    .single()
                    .urls.full,
            )
        assertEquals(200, res.statusCode())
        val img = ImageIO.read(res.bytes().inputStream())
        assertEquals(400 to 300, img.width to img.height)
        assertTrue(res.header("cache-control")!!.contains("immutable"))
    }

    /** Spec `photo-library`: Comment too long › 501 characters. */
    @Test
    fun `A comment of 501 characters rejects the whole patch`() {
        put("a.jpg")
        frame.scan()
        patch(id("a.jpg"), """{"comment":"Grandma's 80th birthday"}""")
        val res = patch(id("a.jpg"), """{"comment":"${"a".repeat(501)}","hidden":true}""")
        assertEquals(400, res.statusCode())
        assertEquals(setOf("comment"), errorFields(res))
        val photo = page().items.single()
        assertEquals("Grandma's 80th birthday", photo.comment, "the photo keeps its comment")
        assertTrue(photo.visible, "and stays visible")
        // 500 after trimming are fine.
        assertEquals(200, patch(id("a.jpg"), """{"comment":"  ${"a".repeat(500)}  "}""").statusCode())
        assertEquals(setOf("comment"), errorFields(patch(id("a.jpg"), """{"comment":5}""")))
    }

    /** Spec `photo-library`: Comment of a photo, through the API. */
    @Test
    fun `Set, normalize and remove a comment`() {
        put("a.jpg")
        frame.scan()
        assertNull(page().items.single().comment)
        assertEquals("Grandma's 80th birthday", photoOf(patch(id("a.jpg"), """{"comment":"  Grandma's 80th birthday "}""")).comment)
        assertEquals("Grandma's birthday", photoOf(patch(id("a.jpg"), """{"comment":"Grandma's\nbirthday"}""")).comment)
        assertNull(photoOf(patch(id("a.jpg"), """{"comment":"   "}""")).comment)
        patch(id("a.jpg"), """{"comment":"again"}""")
        assertNull(photoOf(patch(id("a.jpg"), """{"comment":null}""")).comment)
    }

    /** Spec `photo-library`: Comment and location in the photo list › Set only the comment. */
    @Test
    fun `Set only the comment of a hidden photo`() {
        put("a.jpg")
        frame.scan()
        patch(id("a.jpg"), """{"hidden":true}""")
        val photo = photoOf(patch(id("a.jpg"), """{"comment":"Grandma's 80th birthday"}"""))
        assertEquals("Grandma's 80th birthday", photo.comment)
        assertTrue(photo.hidden, "stays hidden")
    }

    /** Spec `photo-library`: Comment and location in the photo list › Photo with comment and position. */
    @Test
    fun `Photo with comment and position`() {
        put("tuscany/siena.jpg", "gps-siena.jpg")
        put("a.jpg")
        frame.scan()
        patch(id("tuscany/siena.jpg"), """{"comment":"Grandma's 80th birthday"}""")
        val photos = page().items.associateBy { it.path }
        val siena = photos.getValue("tuscany/siena.jpg")
        assertEquals("Grandma's 80th birthday", siena.comment)
        assertEquals(43.3188, siena.location!!.lat, 1e-6)
        assertEquals(11.3308, siena.location!!.lon, 1e-6)
        assertEquals("Siena, Italy", siena.location!!.place, "the frame follows its English system")
        assertNull(photos.getValue("a.jpg").location, "without a position")
        assertNull(photos.getValue("a.jpg").comment)
        // Spec "Language of the place name": in the frame's language, without reading the photo again.
        val probes = frame.engine.probes.get()
        frame.send("PATCH", "/api/v1/settings", """{"language":"de"}""")
        assertEquals(
            "Siena, Italien",
            page()
                .items
                .first { it.path == "tuscany/siena.jpg" }
                .location!!
                .place,
        )
        assertEquals(probes, frame.engine.probes.get())
        assertEquals(0, frame.engine.metadataReads.get())
    }

    @Test
    fun `A section on a photo of a remote source`() {
        val id = putRemote()
        val res = patch(id, """{"crop":{"x":0.25,"y":0.75,"zoom":2}}""")
        assertEquals(200, res.statusCode(), res.body())
        assertEquals(Crop(0.25, 0.75, 2.0), photoOf(res).crop)
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
        // 400×300 on 1024×600 in `auto` crops less than a quarter, so it shows the middle 400×234 as `fill` does,
        // which would cover the screen only when scaled up, so it stays at the original's pixels.
        assertEquals(400 to 234, img.width to img.height)
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

    /** Spec `photo-library`: Image size limits › Very long strip. */
    @Test
    fun `Very long strip`() {
        File(frame.photos, "strip.png").writeBytes(Fixtures.pngOfSize(500_000, 127))
        File(frame.photos, "huge.png").writeBytes(Fixtures.pngOfSize(12_240, 16_320))
        frame.scan()
        val items = ApiJson.decodeFromString(SkippedFileListDto.serializer(), frame.get("/api/v1/skipped-files").body()).items
        assertEquals(mapOf("huge.png" to "too_large", "strip.png" to "too_large"), items.associate { it.path to it.reason })
        assertEquals(0, frame.engine.decodes.get(), "rejected from the bounds, without decoding")
    }

    @Test
    fun `State tells that the frame changes photos instantly`() {
        fun fallback() =
            Json
                .parseToJsonElement(frame.get("/api/v1/state").body())
                .jsonObject["transitionFallback"]!!
                .jsonPrimitive.content
        assertEquals("false", fallback())
        frame.core.transitions.judge(frameMs = 14f, refreshMs = 17.65f)
        assertEquals("true", fallback())
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
