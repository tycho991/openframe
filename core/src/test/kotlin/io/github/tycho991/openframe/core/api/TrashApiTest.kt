package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.health.Health
import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.library.Crop
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.RenderSize
import io.github.tycho991.openframe.core.library.TrashStore
import io.github.tycho991.openframe.core.platform.ImageException
import io.github.tycho991.openframe.core.platform.SkipReason
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.Res
import io.github.tycho991.openframe.core.testing.TestFrame
import io.github.tycho991.openframe.core.testing.TestHttp
import io.github.tycho991.openframe.core.testing.waitFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Spec `photo-library`: Delete, Trash for deleted photos, Restore from the trash, Trash list through the API, Log. */
class TrashApiTest {
    @TempDir
    lateinit var dir: File

    private var opened: TestFrame? = null
    private val frame: TestFrame get() = opened ?: TestFrame(File(dir, "frame")).also { opened = it }

    @AfterEach
    fun tearDown() {
        opened?.close()
    }

    private fun id(path: String) = PhotoEntry.idFor("local", path)

    private fun put(path: String) = Fixtures.copy("landscape.jpg", File(frame.photos, path))

    private fun entry(path: String) = frame.core.library.index.library.value.photos[id(path)]

    private fun trash(auth: Map<String, String> = frame.auth): TrashListDto {
        val res = frame.http.get("/api/v1/trash", auth)
        assertEquals(200, res.statusCode(), res.body())
        return ApiJson.decodeFromString(TrashListDto.serializer(), res.body())
    }

    private fun restore(
        vararg ids: String,
        auth: Map<String, String> = frame.auth,
    ): TrashRestoreResultDto {
        val res = restoreRes(*ids, auth = auth)
        assertEquals(200, res.statusCode(), res.body())
        return ApiJson.decodeFromString(TrashRestoreResultDto.serializer(), res.body())
    }

    private fun restoreRes(
        vararg ids: String,
        auth: Map<String, String> = frame.auth,
    ): Res = frame.http.request("POST", "/api/v1/trash:restore", ids.joinToString(",", """{"ids":[""", "]}") { "\"$it\"" }, auth)

    private fun delete(
        path: String,
        auth: Map<String, String> = frame.auth,
    ) = assertEquals(204, frame.http.request("DELETE", "/api/v1/photos/${id(path)}", null, auth).statusCode())

    private fun errorCode(res: Res) =
        Json
            .parseToJsonElement(res.body())
            .jsonObject["error"]!!
            .jsonObject["code"]!!
            .jsonPrimitive.content

    private fun libraryVersion() = frame.core.library.index.library.value.version

    private fun clientId(name: String) =
        frame.core.clients.clients.value
            .first { it.name == name }
            .id

    private fun authOf(name: String) = mapOf("Authorization" to "Bearer ${frame.pair(name)}")

    /** › Delete › Delete photo; › Trash for deleted photos › Deleted photo leaves the slideshow. */
    @Test
    fun `A deleted photo is in the trash below its path and does not come back with a scan`() {
        put("holiday/beach.jpg")
        put("holiday/other.jpg")
        frame.scan()
        val photo = entry("holiday/beach.jpg")!!
        // A rendered size, which goes with the photo.
        runBlocking { frame.core.cache.get(photo, RenderSize.THUMB) }
        waitFor(message = "in the slideshow") {
            frame.core.visiblePhotos.value
                .any { it.id == photo.id }
        }

        delete("holiday/beach.jpg")

        val item = trash().items.single()
        assertTrue(File(frame.photos, ".trash/${item.id}/holiday/beach.jpg").isFile)
        assertFalse(File(frame.photos, "holiday/beach.jpg").exists())
        assertNull(entry("holiday/beach.jpg"))
        waitFor(message = "out of the slideshow") {
            frame.core.visiblePhotos.value
                .none { it.id == photo.id }
        }
        frame.core.cache.awaitDeletions()
        val renders = File(frame.dirs.cache, "renders-r1").listFiles().orEmpty().map { it.name }
        assertTrue(renders.none { it.startsWith(photo.id) }, renders.toString())
        frame.scan()
        assertNull(entry("holiday/beach.jpg"), "a scan does not bring it back")
        assertEquals(listOf(item.id), trash().items.map { it.id })
    }

    /** › Delete › Batch with Nextcloud photos. */
    @Test
    fun `A batch of a local and a Nextcloud photo moves one and fails the other in one change`() {
        put("a.jpg")
        frame.scan()
        val remote = PhotoEntry(PhotoEntry.idFor("nextcloud", "r.jpg"), "nextcloud", "r.jpg", 400, 300, 0, 1, 1, 1, etag = "e")
        frame.core.library.index
            .edit { it.put(remote) }
        val before = libraryVersion()
        val res = frame.send("POST", "/api/v1/photos:batch", """{"action":"delete","ids":["${id("a.jpg")}","${remote.id}"]}""")
        val result = ApiJson.decodeFromString(BatchResultDto.serializer(), res.body())
        assertEquals(1, result.processed)
        assertEquals(listOf(remote.id), result.failed)
        assertEquals(before + 1, libraryVersion())
        assertEquals(listOf("a.jpg"), trash().items.map { it.path })
    }

    @Test
    fun `A failed move leaves no trash entry and puts the ID in failed`() {
        put("a.jpg")
        put("b.jpg")
        frame.scan()
        frame.core.trash.renameFile = { from, to -> if (from.name == "a.jpg") false else from.renameTo(to) }
        val res = frame.send("POST", "/api/v1/photos:batch", """{"action":"delete","ids":["${id("a.jpg")}","${id("b.jpg")}"]}""")
        val result = ApiJson.decodeFromString(BatchResultDto.serializer(), res.body())
        assertEquals(listOf(id("a.jpg")), result.failed)
        assertEquals(listOf("b.jpg"), trash().items.map { it.path })
        assertTrue(File(frame.photos, "a.jpg").isFile)
        assertTrue(entry("a.jpg") != null)
    }

    /** › Trash list through the API › List after a delete; › Log of trash actions › Deleted and restored by two devices. */
    @Test
    fun `A phone deletes, a laptop lists and restores, and the log names both`() {
        put("holiday/beach.jpg")
        frame.scan()
        val phone = authOf("Phone")
        val laptop = authOf("Laptop")
        delete("holiday/beach.jpg", phone)

        val list = trash(laptop)
        val item = list.items.single()
        assertEquals("holiday/beach.jpg", item.path)
        assertEquals("holiday", item.album)
        assertEquals(id("holiday/beach.jpg"), item.photoId)
        assertEquals(clientId("Phone"), item.deletedBy)
        val deletedAt = Instant.parse(item.deletedAt)
        assertEquals(deletedAt + Duration.ofDays(7), Instant.parse(item.purgeAfter))
        assertEquals(File(frame.photos, ".trash/${item.id}/holiday/beach.jpg").length(), item.fileSize)
        assertEquals(item.fileSize, list.totalBytes)
        assertFalse(item.quarantined || item.damaged)

        val restored = restore(item.id, auth = laptop)
        assertEquals(listOf(RestoredDto(item.id, id("holiday/beach.jpg"))), restored.restored)
        val messages =
            frame.core.log
                .recent(1000)
                .map { it.message }
        assertTrue("Moved to trash: holiday/beach.jpg by ${clientId("Phone")}" in messages, messages.toString())
        assertTrue("Restored: holiday/beach.jpg by ${clientId("Laptop")}" in messages, messages.toString())
        val all =
            frame.core.log
                .recent(1000)
                .joinToString("\n") { it.message }
        for (auth in listOf(phone, laptop, frame.auth)) assertFalse(auth.getValue("Authorization").removePrefix("Bearer ") in all)
    }

    /** › Trash list through the API › Storage unavailable. */
    @Test
    fun `Listing and restoring answer 503 while the storage is not mounted`() {
        put("a.jpg")
        frame.scan()
        delete("a.jpg")
        val trashId = trash().items.single().id
        frame.platform.mounted = false
        val listed = frame.get("/api/v1/trash")
        assertEquals(503, listed.statusCode())
        assertEquals("storage_unavailable", errorCode(listed))
        val restoring = restoreRes(trashId)
        assertEquals(503, restoring.statusCode())
        assertEquals("storage_unavailable", errorCode(restoring))
        frame.platform.mounted = true
        assertEquals(1, trash().items.size, "the entry is kept")
    }

    /** › Trash for deleted photos › No way to empty the trash. */
    @Test
    fun `There is no way to empty the trash`() {
        put("a.jpg")
        frame.scan()
        delete("a.jpg")
        val trashId = trash().items.single().id
        // Outside the contract on purpose, so without its check.
        val raw = TestHttp(frame.http.base, checkContract = false)
        assertEquals(405, raw.request("DELETE", "/api/v1/trash", null, frame.auth).statusCode())
        assertEquals(404, raw.request("DELETE", "/api/v1/trash/$trashId", null, frame.auth).statusCode())
        assertEquals(1, trash().items.size)
    }

    /** › Restore from the trash › Restore a hidden photo with a comment. */
    @Test
    fun `A hidden photo with a comment and a section comes back with its ID and state and never shows meanwhile`() {
        put("a.jpg")
        put("holiday/beach.jpg")
        frame.scan()
        val photoId = id("holiday/beach.jpg")
        val patched =
            frame.send(
                "PATCH",
                "/api/v1/photos/$photoId",
                """{"hidden":true,"comment":"Sunset","crop":{"x":0.4,"y":0.5,"zoom":1.5}}""",
            )
        assertEquals(200, patched.statusCode(), patched.body())
        val before = entry("holiday/beach.jpg")!!
        val seen = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { frame.core.visiblePhotos.collect { list -> list.forEach { seen += it.id } } }
        try {
            delete("holiday/beach.jpg")
            val result = restore(trash().items.single().id)
            assertEquals(photoId, result.restored.single().photoId)
            val back = entry("holiday/beach.jpg")!!
            assertEquals(photoId, back.id)
            assertTrue(back.version > before.version, "a new image version")
            assertTrue(back.hidden)
            assertEquals("Sunset", back.comment)
            assertEquals(Crop(0.4, 0.5, 1.5), back.crop)
            assertTrue(File(frame.photos, "holiday/beach.jpg").isFile)
            assertTrue(trash().items.isEmpty())
            // The restored photo's thumbnail renders: the tombstone of the old version does not drop the job.
            val thumb = frame.get("/api/v1/photos/$photoId/image?size=thumb")
            assertEquals(200, thumb.statusCode(), thumb.body())
            frame.scan()
            assertEquals(back, entry("holiday/beach.jpg"), "a scan keeps it as it is")
        } finally {
            scope.cancel()
        }
        assertFalse(photoId in seen, "the slideshow never showed it")
    }

    /** › Restore from the trash › Path taken meanwhile. */
    @Test
    fun `A restore onto a taken path gets a new name and ID and keeps its state`() {
        put("holiday/beach.jpg")
        frame.scan()
        frame.send("PATCH", "/api/v1/photos/${id("holiday/beach.jpg")}", """{"comment":"Old one"}""")
        delete("holiday/beach.jpg")
        val upload = frame.upload("holiday", "beach.jpg" to Fixtures.file("gps-siena.jpg").readBytes())
        assertEquals(201, upload.statusCode(), upload.body())
        val result = restore(trash().items.single().id)
        assertEquals(id("holiday/beach (1).jpg"), result.restored.single().photoId)
        assertEquals("Old one", entry("holiday/beach (1).jpg")!!.comment)
        assertNull(entry("holiday/beach.jpg")!!.comment, "the upload keeps its name and has its own state")
        assertEquals(Fixtures.file("gps-siena.jpg").length(), File(frame.photos, "holiday/beach.jpg").length())
    }

    /** › Restore from the trash › Album folder gone. */
    @Test
    fun `A removed album folder is created again`() {
        put("trip/2024/a.jpg")
        frame.scan()
        delete("trip/2024/a.jpg")
        assertTrue(File(frame.photos, "trip").deleteRecursively())
        val result = restore(trash().items.single().id)
        assertEquals(id("trip/2024/a.jpg"), result.restored.single().photoId)
        assertTrue(File(frame.photos, "trip/2024/a.jpg").isFile)
        assertEquals("trip/2024", entry("trip/2024/a.jpg")!!.album)
    }

    /** › Restore from the trash › Album folder in another letter case. */
    @Test
    fun `An album restored after an upload into another letter case lands in the existing folder`() {
        put("Trip/a.jpg")
        frame.scan()
        delete("Trip/a.jpg")
        assertTrue(File(frame.photos, "Trip").delete())
        assertEquals(201, frame.upload("trip", "b.jpg" to Fixtures.file("landscape.jpg").readBytes()).statusCode())
        val result = restore(trash().items.single().id)
        assertEquals(id("trip/a.jpg"), result.restored.single().photoId)
        assertTrue(File(frame.photos, "trip/a.jpg").isFile)
        assertFalse(File(frame.photos, "Trip").exists())
        assertEquals("trip", entry("trip/a.jpg")!!.album)
    }

    /** › Restore from the trash › Restore after a restart. */
    @Test
    fun `An entry restores with its ID and state after a restart`() {
        put("holiday/beach.jpg")
        frame.scan()
        frame.send("PATCH", "/api/v1/photos/${id("holiday/beach.jpg")}", """{"hidden":true,"comment":"Kept"}""")
        delete("holiday/beach.jpg")
        frame.close()
        opened = TestFrame(File(dir, "frame"))
        val result = restore(trash().items.single().id)
        assertEquals(id("holiday/beach.jpg"), result.restored.single().photoId)
        val back = entry("holiday/beach.jpg")!!
        assertTrue(back.hidden)
        assertEquals("Kept", back.comment)
    }

    /** › Trash without its saved state › State file lost. */
    @Test
    fun `Without its record an entry is listed without device and thumbnail and restored hidden`() {
        put("a.jpg")
        put("b.jpg")
        frame.scan()
        delete("a.jpg")
        delete("b.jpg")
        frame.close()
        File(dir, "frame/state/trash.json").delete()
        File(dir, "frame/state/trash.json.bak").delete()
        opened = TestFrame(File(dir, "frame"))
        val items = trash().items.associateBy { it.path }
        assertTrue(items.values.all { it.deletedBy == null && it.thumb == null })
        // A probe that crashes the decoder leaves b.jpg in the trash.
        frame.engine.probeFailure = { if (it.name == "b.jpg") ImageException(SkipReason.CRASHED, "The decoder died") else null }
        val result = restore(items.getValue("a.jpg").id, items.getValue("b.jpg").id)
        assertEquals(listOf(id("a.jpg")), result.restored.map { it.photoId })
        assertEquals(listOf(items.getValue("b.jpg").id), result.failed)
        assertTrue(entry("a.jpg")!!.hidden, "hidden without its saved state")
        assertEquals(listOf("b.jpg"), trash().items.map { it.path })
    }

    @Test
    fun `A damaged entry, an unknown ID and a path outside the trash change nothing`() {
        put("a.jpg")
        frame.scan()
        delete("a.jpg")
        val good = trash().items.single().id
        val damagedId = "1795400000000-0000abcd"
        val damaged = File(frame.photos, ".trash/$damagedId").apply { mkdirs() }
        Fixtures.copy("landscape.jpg", File(damaged, "x.jpg"))
        Fixtures.copy("landscape.jpg", File(damaged, "y.jpg"))
        val outside = Fixtures.copy("landscape.jpg", File(frame.photos, "keep.jpg"))
        frame.core.trash.purge()
        val listed = trash().items.single { it.id == damagedId }
        assertTrue(listed.damaged)
        assertNull(listed.path)
        assertNull(listed.thumb)

        val result = restore(good, damagedId, "1795400000000-ffffffff", "../keep.jpg", "../../state")
        assertEquals(listOf(good), result.restored.map { it.id })
        assertEquals(listOf(damagedId), result.failed)
        assertEquals(listOf("1795400000000-ffffffff", "../keep.jpg", "../../state"), result.missing)
        assertTrue(outside.isFile)
        assertTrue(File(damaged, "x.jpg").isFile && File(damaged, "y.jpg").isFile, "the damaged entry moved nothing")
    }

    @Test
    fun `Restoring answers 400 for no or too many IDs`() {
        assertEquals(400, restoreRes().statusCode())
        val many = (0..10_000).map { "1795400000000-%08x".format(it) }.toTypedArray()
        assertEquals(400, restoreRes(*many).statusCode())
    }

    /** › Restore from the trash › Safe mode. */
    @Test
    fun `In safe mode restoring answers 503 and the entry stays`() {
        put("a.jpg")
        frame.scan()
        delete("a.jpg")
        val trashId = trash().items.single().id
        frame.close()
        opened = TestFrame(File(dir, "frame"), health = Health.safe(System.currentTimeMillis()))
        assertEquals(1, trash().items.size, "listing works in safe mode")
        val res = restoreRes(trashId)
        assertEquals(503, res.statusCode())
        assertEquals("safe_mode", errorCode(res))
        assertTrue(File(frame.photos, ".trash/$trashId/a.jpg").isFile)
        val thumb = frame.get("/api/v1/trash/$trashId/thumb")
        assertEquals(503, thumb.statusCode())
        assertEquals("safe_mode", errorCode(thumb))
    }

    @Test
    fun `A trash entry's thumbnail renders with a token and has no restore of the old ID`() {
        put("a.jpg")
        frame.scan()
        delete("a.jpg")
        val item = trash().items.single()
        val thumb = frame.get("/api/v1/trash/${item.id}/thumb")
        assertEquals(200, thumb.statusCode(), thumb.body())
        assertEquals("private, max-age=31536000, immutable", thumb.header("cache-control"))
        val etag = thumb.header("etag")!!
        val again = frame.http.get("/api/v1/trash/${item.id}/thumb", frame.auth + ("If-None-Match" to etag))
        assertEquals(304, again.statusCode())
        assertEquals(404, frame.get("/api/v1/trash/1795400000000-0000ffff/thumb").statusCode())
        assertNotEquals(null, item.thumb)
        assertTrue(TrashStore.PATTERN.matches(item.id))
    }

    /** Review finding: a record takes the entry as it is at its move, also when it changed after the batch began. */
    @Test
    fun `A change saved during a batch delete is kept in the trash record`() {
        put("a.jpg")
        put("b.jpg")
        frame.scan()
        frame.core.trash.renameFile = { from, to ->
            // While a.jpg moves, another device hides b.jpg and gives it a comment.
            if (from.name == "a.jpg") frame.core.library.update(id("b.jpg")) { it.copy(hidden = true, comment = "Late") }
            from.renameTo(to)
        }
        val res = frame.send("POST", "/api/v1/photos:batch", """{"action":"delete","ids":["${id("a.jpg")}","${id("b.jpg")}"]}""")
        assertEquals(2, ApiJson.decodeFromString(BatchResultDto.serializer(), res.body()).processed)
        val record =
            frame.core.trash
                .items()
                .single { it.path == "b.jpg" }
                .record!!
                .entry
        assertTrue(record.hidden)
        assertEquals("Late", record.comment)
    }

    /** Review finding: an entry a restore put at the same path while the batch moved files stays in the index. */
    @Test
    fun `A photo restored to a path during a batch delete keeps its index entry`() {
        put("a.jpg")
        frame.scan()
        var restored: PhotoEntry? = null
        frame.core.trash.renameFile = { from, to ->
            val ok = from.renameTo(to)
            if (ok && from.name == "a.jpg" && restored == null) {
                // A restore of an older a.jpg lands at the freed path, with a new version and its quarantine.
                Fixtures.copy("gps-siena.jpg", from)
                frame.core.library.index.edit { edit ->
                    val v = edit.newVersion
                    restored =
                        PhotoEntry(
                            id("a.jpg"),
                            "local",
                            "a.jpg",
                            800,
                            600,
                            1,
                            from.length(),
                            from.lastModified(),
                            v,
                            quarantinedVersion = v,
                        ).also(edit::put)
                }
            }
            ok
        }
        delete("a.jpg")
        assertEquals(restored, entry("a.jpg"), "the restored entry stays as it was put")
        frame.scan()
        assertTrue(entry("a.jpg")!!.quarantined)
    }

    /** › Trash without its saved state › Deleted from the trash over USB: its folders left behind. */
    @Test
    fun `An entry whose file was deleted over USB restores as missing`() {
        put("holiday/a.jpg")
        frame.scan()
        delete("holiday/a.jpg")
        val item = trash().items.single()
        assertTrue(File(frame.photos, ".trash/${item.id}/holiday/a.jpg").delete())
        val result = restore(item.id)
        assertEquals(listOf(item.id), result.missing)
        assertTrue(trash().items.isEmpty())
    }

    /** Review finding: an entry whose file goes between its check and the restore's edit is missing, not failed. */
    @Test
    fun `An entry whose file goes while the restore resolves its album restores as missing`() {
        put("holiday/a.jpg")
        frame.scan()
        delete("holiday/a.jpg")
        val item = trash().items.single()
        // Under the restore's locks, while the album is resolved, the file is deleted over USB.
        frame.core.library.listNames = {
            File(frame.photos, ".trash/${item.id}/holiday/a.jpg").delete()
            it.list()
        }
        val result = restore(item.id)
        assertEquals(listOf(item.id), result.missing)
        assertEquals(emptyList<String>(), result.failed)
    }

    /** Review finding: a restore resolves each album once per request. */
    @Test
    fun `A restore of many photos of one album lists its folder once`() {
        for (n in 1..5) put("holiday/$n.jpg")
        frame.scan()
        val res =
            frame.send(
                "POST",
                "/api/v1/photos:batch",
                (1..5).joinToString(",", """{"action":"delete","ids":[""", "]}") {
                    "\"${id("holiday/$it.jpg")}\""
                },
            )
        assertEquals(200, res.statusCode(), res.body())
        val listings =
            java.util.concurrent.atomic
                .AtomicInteger()
        frame.core.library.listNames = {
            listings.incrementAndGet()
            it.list()
        }
        val result = restore(*trash().items.map { it.id }.toTypedArray())
        assertEquals(5, result.restored.size)
        assertEquals(1, listings.get(), "the photo folder is listed once to resolve the album")
    }
}
