package io.github.tycho991.openframe.core.http

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.testing.TestClock
import io.github.tycho991.openframe.core.testing.TestHttp
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class HttpPipelineTest {
    @Serializable
    data class Echo(
        val text: String,
    )

    private val router =
        Router().apply {
            get(
                "/api/v1/hello/{name}",
                Access.PUBLIC,
            ) { req -> HttpResponse.json(Echo.serializer(), Echo("Hello ${req.pathParam("name")}")) }
            post("/api/v1/photos:batch", Access.PUBLIC) { HttpResponse.json(Echo.serializer(), Echo("batch")) }
            post("/api/v1/echo", Access.PUBLIC) { req -> HttpResponse.json(Echo.serializer(), req.bodyAs(Echo.serializer()), 201) }
            get("/api/v1/secret") { HttpResponse.noContent() }
            get("/api/v1/boom", Access.PUBLIC) { error("broken") }
            get("/api/v1/teapot", Access.PUBLIC) { throw HttpException(429, "locked", "Locked", headers = mapOf("Retry-After" to "60")) }
            get("/api/v1/file", Access.PUBLIC) { HttpResponse.FileBody(200, file, "application/octet-stream") }
        }

    private val file: File = Files.createTempFile("range", ".bin").toFile().apply { writeBytes(ByteArray(100) { it.toByte() }) }

    private val authenticator =
        object : Authenticator {
            override fun clientFor(request: HttpRequest) = if (request.header("authorization") == "Bearer good") "c1" else null

            override fun hasValidImageSignature(
                request: HttpRequest,
                photoId: String,
            ) = false
        }

    private val pipeline = HttpPipeline(router, authenticator, AppLog(TestClock()))
    private val http = TestHttp(pipeline::handle)

    @AfterEach
    fun tearDown() {
        http.close()
        file.delete()
    }

    @Test
    fun `Route with path parameter`() {
        val res = http.get("/api/v1/hello/World")
        assertEquals(200, res.statusCode())
        assertEquals("""{"text":"Hello World"}""", res.body())
        assertTrue(
            res
                .headers()
                .firstValue("content-type")
                .get()
                .startsWith("application/json"),
        )
    }

    @Test
    fun `Literal with colon`() {
        assertEquals("""{"text":"batch"}""", http.request("POST", "/api/v1/photos:batch").body())
    }

    @Test
    fun `JSON body is read`() {
        val res = http.request("POST", "/api/v1/echo", """{"text":"x"}""")
        assertEquals(201, res.statusCode())
        assertEquals("""{"text":"x"}""", res.body())
    }

    @Test
    fun `Errors are mapped uniformly`() {
        assertEquals(400, http.request("POST", "/api/v1/echo", "{broken").statusCode())
        assertEquals(400, http.request("POST", "/api/v1/echo", """{"text":"x","more":1}""").statusCode())
        assertEquals(415, http.request("POST", "/api/v1/echo", "x", contentType = "text/plain").statusCode())
        assertEquals(413, http.request("POST", "/api/v1/echo", "\"" + "x".repeat(1_100_000) + "\"").statusCode())
        assertEquals(401, http.get("/api/v1/secret").statusCode())
        assertEquals(204, http.get("/api/v1/secret", mapOf("Authorization" to "Bearer good")).statusCode())
        val boom = http.get("/api/v1/boom")
        assertEquals(500, boom.statusCode())
        assertEquals("""{"error":{"code":"internal_error","message":"Internal error"}}""", boom.body())
        val locked = http.get("/api/v1/teapot")
        assertEquals(429, locked.statusCode())
        assertEquals("60", locked.headers().firstValue("retry-after").get())
    }

    @Test
    fun `Unknown paths and methods`() {
        val res = http.get("/api/v1/gibtsnicht")
        assertEquals(404, res.statusCode())
        assertTrue(res.body().contains("not_found"))
        val wrong = http.request("DELETE", "/api/v1/hello/x")
        assertEquals(405, wrong.statusCode())
        assertEquals("GET", wrong.headers().firstValue("allow").get())
        assertEquals(404, http.get("/something").statusCode())
    }

    @Test
    fun `Connection stays usable after an unread body`() {
        repeat(3) {
            assertEquals(204, http.request("GET", "/api/v1/secret", headers = mapOf("Authorization" to "Bearer good")).statusCode())
            assertEquals(200, http.request("POST", "/api/v1/photos:batch", """{"unread":true}""").statusCode())
        }
    }

    @Test
    fun `Range on files`() {
        val part = http.requestBytes("GET", "/api/v1/file", headers = mapOf("Range" to "bytes=10-19"))
        assertEquals(206, part.statusCode())
        assertEquals((10..19).map { it.toByte() }, part.bytes().toList())
        assertEquals("bytes 10-19/100", part.headers().firstValue("content-range").get())
        assertEquals(416, http.requestBytes("GET", "/api/v1/file", headers = mapOf("Range" to "bytes=200-")).statusCode())
        assertEquals(100, http.requestBytes("GET", "/api/v1/file").bytes().size)
    }

    @Test
    fun `Range parser`() {
        assertEquals(0L..99L, NanoHttpServer.parseRange("bytes=0-", 100))
        assertEquals(90L..99L, NanoHttpServer.parseRange("bytes=-10", 100))
        assertEquals(5L..99L, NanoHttpServer.parseRange("bytes=5-500", 100))
        assertTrue(NanoHttpServer.parseRange("bytes=100-", 100)!!.isEmpty())
        assertEquals(null, NanoHttpServer.parseRange("items=1-2", 100))
    }
}
