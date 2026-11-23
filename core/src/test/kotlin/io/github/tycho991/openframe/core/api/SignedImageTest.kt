package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.TestFrame
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `admin-access`: Protected endpoints › Signature after the device was removed, with the contract check on. */
class SignedImageTest {
    @TempDir
    lateinit var dir: File

    private val frame by lazy { TestFrame(dir) }

    @AfterEach
    fun tearDown() = frame.close()

    private fun thumbFor(auth: Map<String, String>): String {
        val page = ApiJson.decodeFromString(PhotoPageDto.serializer(), frame.http.get("/api/v1/photos", auth).body())
        return page.items
            .single()
            .urls.thumb
    }

    @Test
    fun `Signature after the device was removed`() {
        Fixtures.copy("landscape.jpg", File(frame.photos, "a.jpg"))
        frame.scan()
        val phone = mapOf("Authorization" to "Bearer ${frame.pair("Phone")}")
        val phoneUrl = thumbFor(phone)
        val ownUrl = thumbFor(frame.auth)
        assertNotEquals(phoneUrl, ownUrl, "Each device signs its own URLs")
        assertEquals(200, frame.http.get(phoneUrl).statusCode())

        val phoneId =
            frame.core.clients.clients.value
                .first { it.name == "Phone" }
                .id
        assertEquals(204, frame.send("DELETE", "/api/v1/clients/$phoneId").statusCode())

        // Within the two hours, without a token: the removed device's URL no longer works, the others' still do.
        assertEquals(401, frame.http.get(phoneUrl).statusCode())
        assertEquals(200, frame.http.get(ownUrl).statusCode())
    }

    private fun trashOf(auth: Map<String, String>) =
        ApiJson.decodeFromString(TrashListDto.serializer(), frame.http.get("/api/v1/trash", auth).body()).items

    private fun renderings() = File(frame.dirs.cache, "renders-r1").list().orEmpty().filter { it.startsWith("trash-") }

    /**
     * Spec `admin-access`: Protected endpoints › Signed thumbnail of a trash entry; `photo-library`: Thumbnails in the
     * trash › Thumbnail of a deleted photo, › Signature of a photo for a trash entry.
     */
    @Test
    fun `A trash thumbnail opens with its own signature only, while its device stays paired`() {
        for (name in listOf("a.jpg", "b.jpg", "c.jpg")) Fixtures.copy("landscape.jpg", File(frame.photos, name))
        frame.scan()
        val phone = mapOf("Authorization" to "Bearer ${frame.pair("Phone")}")
        val photoUrl =
            ApiJson
                .decodeFromString(PhotoPageDto.serializer(), frame.http.get("/api/v1/photos", phone).body())
                .items
                .single { it.path == "c.jpg" }
                .urls.thumb
        for (name in listOf("a.jpg", "b.jpg")) {
            assertEquals(204, frame.send("DELETE", "/api/v1/photos/${PhotoEntry.idFor("local", name)}").statusCode())
        }
        val items = trashOf(phone).associateBy { it.path }
        val a = items.getValue("a.jpg")
        val b = items.getValue("b.jpg")
        val aUrl = a.thumb!!
        assertTrue(aUrl.startsWith("/api/v1/trash/${a.id}/thumb?t="), aUrl)
        val served = frame.http.get(aUrl)
        assertEquals(200, served.statusCode(), served.body())
        assertEquals("image/jpeg", served.header("content-type"))

        val signature = aUrl.substringAfter("t=")
        assertEquals(401, frame.http.get("/api/v1/trash/${b.id}/thumb?t=$signature").statusCode(), "another trash entry")
        val photoSignature = photoUrl.substringAfter("t=").substringBefore('&')
        assertEquals(401, frame.http.get("/api/v1/trash/${a.id}/thumb?t=$photoSignature").statusCode(), "a photo's signature")
        assertEquals(
            401,
            frame.http.get("/api/v1/photos/${PhotoEntry.idFor("local", "c.jpg")}/image?size=thumb&t=$signature").statusCode(),
            "a trash signature for a photo",
        )

        val phoneId =
            frame.core.clients.clients.value
                .first { it.name == "Phone" }
                .id
        assertEquals(204, frame.send("DELETE", "/api/v1/clients/$phoneId").statusCode())
        assertEquals(401, frame.http.get(aUrl).statusCode(), "after the device was removed")
    }

    /** Design decision 10 of `paired-device-guards`: renderings of trash entries go on restore and at the start. */
    @Test
    fun `Trash thumbnails go on restore and at the start, and render again on request`() {
        val folder = File(dir, "restart")

        fun renderings(f: TestFrame) = File(f.dirs.cache, "renders-r1").list().orEmpty().filter { it.startsWith("trash-") }

        fun trash(f: TestFrame) = ApiJson.decodeFromString(TrashListDto.serializer(), f.get("/api/v1/trash").body()).items
        TestFrame(folder).use { first ->
            for (name in listOf("a.jpg", "b.jpg")) Fixtures.copy("landscape.jpg", File(first.photos, name))
            first.scan()
            for (name in listOf("a.jpg", "b.jpg")) {
                assertEquals(204, first.send("DELETE", "/api/v1/photos/${PhotoEntry.idFor("local", name)}").statusCode())
            }
            val items = trash(first).associateBy { it.path }
            for (item in items.values) assertEquals(200, first.http.get(item.thumb!!).statusCode())
            assertEquals(2, renderings(first).size)
            val a = items.getValue("a.jpg")
            assertEquals(200, first.send("POST", "/api/v1/trash:restore", """{"ids":["${a.id}"]}""").statusCode())
            first.core.cache.awaitDeletions()
            assertEquals(1, renderings(first).size, "the restored entry's thumbnail went")
        }
        TestFrame(folder).use { again ->
            assertEquals(emptyList<String>(), renderings(again), "the start sweep removed them")
            val b = trash(again).single()
            assertEquals(200, again.http.get(b.thumb!!).statusCode(), "renders again on request")
        }
    }
}
