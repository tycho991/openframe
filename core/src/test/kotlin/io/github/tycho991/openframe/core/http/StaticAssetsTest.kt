package io.github.tycho991.openframe.core.http

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.AssetSource
import io.github.tycho991.openframe.core.testing.TestClock
import io.github.tycho991.openframe.core.testing.TestHttp
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StaticAssetsTest {
    private val files =
        mapOf(
            "index.html" to "<!doctype html><title>openframe</title>",
            "assets/index-abc.js" to "console.log(1)",
            "assets/index-abc.css" to "body{}",
        )
    private val assets = AssetSource { path -> files[path]?.byteInputStream() }
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
    fun `index für Wurzel und SPA-Routen`() {
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
    fun `Assets mit MIME-Typ und langem Cache`() {
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
    fun `Fehlende Datei und API-Pfade`() {
        assertEquals(404, http.get("/assets/fehlt.js").statusCode())
        assertEquals(404, http.get("/api/v1/fehlt").statusCode())
        assertEquals(404, http.get("/../etc/passwd.txt").statusCode())
    }
}
