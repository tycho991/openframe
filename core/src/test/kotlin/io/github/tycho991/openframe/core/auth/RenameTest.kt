package io.github.tycho991.openframe.core.auth

import io.github.tycho991.openframe.core.api.ClientDto
import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.testing.PairingHarness
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `admin-access`: Rename paired devices. */
class RenameTest {
    @TempDir
    lateinit var dir: File

    private val h by lazy { PairingHarness(dir) }

    @AfterEach
    fun tearDown() = h.close()

    private fun rename(
        auth: Map<String, String>,
        id: String,
        name: String,
    ) = h.http.request("PATCH", "/api/v1/clients/$id", """{"name":"$name"}""", auth)

    @Test
    fun `Rename`() {
        val laptop = h.pairedAuth("Laptop")
        val phone = h.pairedAuth("Safari on iPhone")
        val id =
            h.clients.clients.value
                .first { it.name == "Safari on iPhone" }
                .id

        // Any paired device may rename any other.
        val res = rename(laptop, id, "  Anna's iPhone  ")
        assertEquals(200, res.statusCode())
        val client = ApiJson.decodeFromString(ClientDto.serializer(), res.body())
        assertEquals("Anna's iPhone", client.name)
        assertFalse(client.current)
        assertTrue(ApiJson.decodeFromString(ClientDto.serializer(), rename(phone, id, "Anna's iPhone").body()).current)

        val list = Json.parseToJsonElement(h.http.get("/api/v1/clients", laptop).body()).jsonObject["clients"]!!.jsonArray
        assertEquals(listOf("Laptop", "Anna's iPhone"), list.map { it.jsonObject["name"]!!.jsonPrimitive.content })

        // Survives a restart.
        assertEquals(
            listOf("Laptop", "Anna's iPhone"),
            h
                .newRegistry()
                .also { it.load() }
                .clients.value
                .map { it.name },
        )
    }

    @Test
    fun `Invalid names and unknown devices`() {
        val laptop = h.pairedAuth("Laptop")
        val id =
            h.clients.clients.value
                .single()
                .id
        assertEquals(400, rename(laptop, id, "   ").statusCode())
        assertEquals(400, rename(laptop, id, "x".repeat(65)).statusCode())
        assertEquals(200, rename(laptop, id, "x".repeat(64)).statusCode())
        assertEquals(404, rename(laptop, "unknown", "Name").statusCode())
    }

    /** Spec `admin-access`: Device names without control characters. */
    @Test
    fun `Rename to only control characters`() {
        val laptop = h.pairedAuth("Laptop")
        val id =
            h.clients.clients.value
                .single()
                .id
        assertEquals(400, rename(laptop, id, "\\r\\n\\u0007").statusCode())
        assertEquals(
            "Laptop",
            h.clients.clients.value
                .single()
                .name,
        )
        val res = rename(laptop, id, "Tablet\\rX")
        assertEquals("Tablet X", ApiJson.decodeFromString(ClientDto.serializer(), res.body()).name)
    }
}
