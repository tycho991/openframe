package io.github.tycho991.openframe.core.http

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.log.LogLevel
import io.github.tycho991.openframe.core.platform.AssetSource
import io.github.tycho991.openframe.core.testing.FakeAuth
import io.github.tycho991.openframe.core.testing.TestClock
import io.github.tycho991.openframe.core.testing.TestHttp
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * finish-lan-hardening, design decisions 6 to 8: answers whose file or stream fails still leave as JSON with the
 * security headers, range answers close their file, and HEAD sends the headers of GET without a body.
 */
class ResponseErrorsTest {
    @TempDir
    lateinit var dir: File

    private val log = AppLog(TestClock())
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterEach
    fun tearDown() = closeables.reversed().forEach { it.close() }

    private fun server(handler: suspend (HttpRequest) -> HttpResponse): Pair<NanoHttpServer, TestHttp> {
        val server =
            NanoHttpServer("127.0.0.1", 0, Dispatchers.IO.limitedParallelism(4), handler, log = log).also { it.startServer() }
        closeables += AutoCloseable { server.stop() }
        val http = TestHttp("http://127.0.0.1:${server.port}", checkContract = true)
        return server to http
    }

    private fun image(): File = File(dir, "image.jpg").apply { writeBytes(ByteArray(10_000) { (it % 251).toByte() }) }

    private val imagePath = "/api/v1/photos/local-0000000000000000000000000000000000000000/image?size=display"

    @Test
    fun `Range answers close their file`() {
        val file = image()
        val (server, http) = server { HttpResponse.FileBody(200, file, "image/jpeg") }
        repeat(100) { i ->
            val res = http.get(imagePath, mapOf("Range" to "bytes=${i * 10}-${i * 10 + 99}"))
            assertEquals(206, res.statusCode())
            assertEquals(100, res.bytes().size)
            assertEquals("bytes ${i * 10}-${i * 10 + 99}/10000", res.header("content-range"))
        }
        val full = http.get(imagePath)
        assertEquals(10_000, full.bytes().size)
        val outside = http.get(imagePath, mapOf("Range" to "bytes=20000-"))
        assertEquals(416, outside.statusCode())
        assertEquals("bytes */10000", outside.header("content-range"))
        // NanoHTTPD closes the stream after the last byte went out, which can be a moment after the client has it.
        val deadline = System.currentTimeMillis() + 5_000
        while (server.openFiles.get() != 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(0, server.openFiles.get())
    }

    @Test
    fun `A file gone before the open gives JSON 404 without its path`() {
        val file = File(dir, "gone.jpg")
        val (_, http) = server { HttpResponse.FileBody(200, file, "image/jpeg") }
        for (headers in listOf(emptyMap(), mapOf("Range" to "bytes=0-99"))) {
            val res = http.get(imagePath, headers)
            assertEquals(404, res.statusCode(), res.body())
            assertTrue("\"not_found\"" in res.body(), res.body())
            assertFalse(dir.path in res.body() || "gone.jpg" in res.body(), res.body())
            assertEquals("nosniff", res.header("x-content-type-options"))
        }
    }

    @Test
    fun `A missing asset gives JSON 404`() {
        val opens = AtomicInteger()
        val assets = AssetSource { if (opens.incrementAndGet() == 1) ByteArrayInputStream("<html></html>".toByteArray()) else null }
        val (_, http) = server(StaticAssets(assets)::handle)
        val res = http.get("/")
        assertEquals(404, res.statusCode(), res.body())
        assertTrue("\"not_found\"" in res.body(), res.body())
        assertEquals("nosniff", res.header("x-content-type-options"))
    }

    @Test
    fun `Any other error while opening gives JSON 500 and a log line`() {
        for (error in listOf(IllegalStateException("broken"), IOException("EIO"))) {
            val (_, http) = server { HttpResponse.StreamBody(200, "text/plain", { throw error }, null) }
            val res = http.get("/x.txt")
            assertEquals(500, res.statusCode(), res.body())
            assertTrue("\"internal_error\"" in res.body(), res.body())
            assertEquals("nosniff", res.header("x-content-type-options"))
        }
        assertEquals(2, log.recent(100).count { it.level == LogLevel.ERROR && "while opening the answer" in it.message })
    }

    @Test
    fun `Unknown methods get 405 with the security headers`() {
        val (_, http) = server { HttpResponse.Json(200, "{}") }
        for (method in listOf("TRACE", "PROPFIND")) {
            val res = http.raw("$method /api/v1/info HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
            assertEquals(405, res.status, res.body)
            assertEquals("nosniff", res.headers["x-content-type-options"])
            assertTrue("GET" in (res.headers["allow"] ?: ""), res.headers.toString())
        }
    }

    @Test
    fun `HEAD sends the headers of GET and no body`() {
        val page = "<!doctype html><title>openframe</title>" + "x".repeat(5_000)
        val pipeline =
            HttpPipeline(
                Router().apply { get("/api/v1/info", Access.PUBLIC) { HttpResponse.Json(200, "{}") } },
                FakeAuth(),
                log,
                StaticAssets { ByteArrayInputStream(page.toByteArray()) }::handle,
            )
        val (_, http) = server(pipeline::handle)
        val get = "GET / HTTP/1.1\r\nHost: x\r\n\r\n"
        val answers =
            http.rawSequence(
                listOf(
                    "HEAD / HTTP/1.1\r\nHost: x\r\n\r\n",
                    "HEAD / HTTP/1.1\r\nHost: x\r\nAccept-Encoding: gzip\r\n\r\n",
                    get,
                ),
            )
        val (head, headGzip, full) = answers
        assertEquals(page.toByteArray().size, full.bodyBytes)
        for (h in listOf(head, headGzip)) {
            assertEquals(200, h.status)
            assertEquals(listOf(page.toByteArray().size.toString()), h.headerLists["content-length"], h.headerLists.toString())
            assertEquals(null, h.headers["content-encoding"])
            assertEquals(null, h.headers["transfer-encoding"])
        }
        // Had a HEAD sent a body, the GET would have read it as its own start and failed above.
        assertEquals(page, full.body)
        val api = http.raw("HEAD /api/v1/info HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
        assertEquals(405, api.status)
    }
}
