package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.TestFrame
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
        return page.items.single().urls.thumb
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
}
