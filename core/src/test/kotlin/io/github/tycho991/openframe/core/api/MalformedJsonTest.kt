package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.http.HttpRequest
import io.github.tycho991.openframe.core.log.LogLevel
import io.github.tycho991.openframe.core.testing.Res
import io.github.tycho991.openframe.core.testing.TestFrame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `admin-access`: Malformed JSON bodies, with the contract check on. */
class MalformedJsonTest {
    @TempDir
    lateinit var dir: File

    private val frame by lazy { TestFrame(dir) }

    @AfterEach
    fun tearDown() = frame.close()

    private fun error(res: Res) = Json.parseToJsonElement(res.body()).jsonObject["error"]!!.jsonObject

    private fun code(res: Res) = error(res)["code"]!!.jsonPrimitive.content

    private fun message(res: Res) = error(res)["message"]!!.jsonPrimitive.content

    private fun noErrorInLog() =
        assertFalse(
            frame.core.log
                .recent(500)
                .any { it.level == LogLevel.ERROR },
            "${frame.core.log.recent(500)}",
        )

    @Test
    fun `Deep nesting`() {
        val res = frame.send("PATCH", "/api/v1/settings", "[".repeat(100_000))
        assertEquals(400, res.statusCode())
        assertEquals("invalid_json", code(res))
        noErrorInLog()
    }

    @Test
    fun `64 levels still parse`() {
        val deep = "[".repeat(64) + "]".repeat(64)
        val res = frame.send("PATCH", "/api/v1/settings", deep)
        assertEquals(400, res.statusCode())
        assertEquals("bad_request", code(res), "parsed, but not settings")
        val deeper = "[".repeat(65) + "]".repeat(65)
        assertEquals("invalid_json", code(frame.send("PATCH", "/api/v1/settings", deeper)))
        // Brackets inside strings do not count.
        val inString = """{"x":"${"[".repeat(100)}\"${"{".repeat(100)}"}"""
        assertEquals(1, HttpRequest.nesting(inString.toByteArray()))
    }

    @Test
    fun `Deep nesting without a token`() {
        val res = frame.http.request("POST", "/api/v1/pairing", "[".repeat(4_000))
        assertEquals(400, res.statusCode())
        assertEquals("invalid_json", code(res))
        noErrorInLog()
    }

    @Test
    fun `Broken JSON`() {
        val res = frame.send("PATCH", "/api/v1/settings", """{"clock" "secretvalue"}""")
        assertEquals(400, res.statusCode())
        assertEquals("invalid_json", code(res))
        val text = message(res)
        assertTrue(Regex("Not valid JSON at offset [0-9]+").matches(text), text)
        assertFalse("secretvalue" in res.body(), res.body())
        assertFalse('\n' in text)
    }

    @Test
    fun `An unknown field names no class`() {
        val id =
            frame.core.clients.clients.value
                .first()
                .id
        val res = frame.send("PATCH", "/api/v1/clients/$id", """{"name":"Phone","secretfield":1}""")
        assertEquals(400, res.statusCode())
        val text = message(res)
        for (part in listOf("io.github", "Dto", "kotlinx", "secretfield", "Use 'ignoreUnknownKeys")) assertFalse(part in text, text)
        val missing = frame.send("PATCH", "/api/v1/clients/$id", "{}")
        assertEquals("Invalid body: missing name", message(missing))
    }
}
