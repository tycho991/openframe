package io.github.tycho991.openframe.core.http

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.AssetSource
import io.github.tycho991.openframe.core.testing.TestClock
import io.github.tycho991.openframe.core.testing.TestHttp
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class StaticAssetsTest {
    private val files =
        mapOf(
            "index.html" to "<!doctype html><title>openframe</title>",
            "assets/index-abc.js" to "console.log(1)",
            "assets/index-abc.css" to "body{}",
        )

    /** Every path the asset source was asked for. A real one would resolve `..` against a folder or the APK. */
    private val requested = CopyOnWriteArrayList<String>()
    private val assets =
        AssetSource { path ->
            requested += path
            files[path]?.byteInputStream()
        }
    private val noAuth =
        object : Authenticator {
            override fun clientFor(request: HttpRequest): String? = null

            override fun hasValidImageSignature(
                request: HttpRequest,
                photoId: String,
            ) = false
        }
    private val http = TestHttp(HttpPipeline(Router(), noAuth, AppLog(TestClock()), StaticAssets(assets)::handle)::handle)

    @AfterEach
    fun tearDown() = http.close()

    @Test
    fun `index for root and SPA routes`() {
        for (path in listOf("/", "/photos", "/settings/clock")) {
            val res = http.get(path)
            assertEquals(200, res.statusCode(), path)
            assertEquals(files["index.html"], res.body())
            assertTrue(
                res
                    .headers()
                    .firstValue("content-type")
                    .get()
                    .startsWith("text/html"),
            )
            assertTrue(
                res
                    .headers()
                    .firstValue("content-security-policy")
                    .get()
                    .contains("default-src 'self'"),
            )
            assertEquals("no-cache", res.headers().firstValue("cache-control").get())
        }
    }

    @Test
    fun `Assets with MIME type and long cache`() {
        val js = http.get("/assets/index-abc.js")
        assertEquals(200, js.statusCode())
        assertTrue(
            js
                .headers()
                .firstValue("content-type")
                .get()
                .startsWith("text/javascript"),
        )
        assertTrue(
            js
                .headers()
                .firstValue("cache-control")
                .get()
                .contains("immutable"),
        )
        assertTrue(
            http
                .get("/assets/index-abc.css")
                .headers()
                .firstValue("content-type")
                .get()
                .startsWith("text/css"),
        )
    }

    @Test
    fun `Missing file and API paths`() {
        assertEquals(404, http.get("/assets/missing.js").statusCode())
        assertEquals(404, http.get("/api/v1/missing").statusCode())
        assertEquals(404, http.get("/../etc/passwd.txt").statusCode())
    }

    @Test
    fun `Traversal never reaches the asset source`() {
        // The client sends the paths as they are; the server decodes `%2e%2e` before the handler sees it.
        val paths = listOf("/../etc/passwd.txt", "/./index.html", "/assets/../index.html", "/%2e%2e/x.txt", "/assets/%2e%2e/index.html")
        for (path in paths) assertEquals(404, http.get(path).statusCode(), path)
        assertEquals(emptyList<String>(), requested.filter { p -> p.split('/').any { it == ".." || it == "." } })
    }
}
