package io.github.tycho991.openframe.core.http

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.testing.TestClock
import io.github.tycho991.openframe.core.testing.TestHttp
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Spec `admin-access`: Security headers. */
class SecurityTest {
    private val router =
        Router().apply {
            post("/api/v1/change", Access.PUBLIC) { HttpResponse.noContent() }
            get("/api/v1/read", Access.PUBLIC) { HttpResponse.noContent() }
        }
    private val noAuth =
        object : Authenticator {
            override fun clientFor(request: HttpRequest): String? = null

            override fun hasValidImageSignature(
                request: HttpRequest,
                photoId: String,
            ) = false
        }
    private val http = TestHttp(HttpPipeline(router, noAuth, AppLog(TestClock()))::handle)

    @AfterEach
    fun tearDown() = http.close()

    @Test
    fun `Foreign page`() {
        val host = http.base.removePrefix("http://")
        assertEquals(403, http.request("POST", "/api/v1/change", headers = mapOf("Origin" to "http://evil.example")).statusCode())
        assertEquals(403, http.request("POST", "/api/v1/change", headers = mapOf("Sec-Fetch-Site" to "cross-site")).statusCode())
        assertEquals(403, http.request("POST", "/api/v1/change", headers = mapOf("Sec-Fetch-Site" to "same-site")).statusCode())
        // Non-API paths are checked for mutating methods as well.
        assertEquals(403, http.request("POST", "/anywhere", headers = mapOf("Origin" to "http://evil.example")).statusCode())
        // Own origin, browser navigation and clients without browser headers are allowed.
        assertEquals(204, http.request("POST", "/api/v1/change", headers = mapOf("Origin" to "http://$host")).statusCode())
        assertEquals(204, http.request("POST", "/api/v1/change", headers = mapOf("Sec-Fetch-Site" to "same-origin")).statusCode())
        assertEquals(204, http.request("POST", "/api/v1/change").statusCode())
        // Reading requests from foreign pages are no CSRF risk and are not checked.
        assertEquals(204, http.request("GET", "/api/v1/read", headers = mapOf("Origin" to "http://evil.example")).statusCode())
    }

    @Test
    fun `All responses carry the security headers`() {
        val responses =
            listOf(
                http.get("/api/v1/read"),
                http.get("/api/v1/doesnotexist"),
                http.get("/web"),
                http.request("POST", "/api/v1/change", headers = mapOf("Origin" to "http://evil.example")),
            )
        for (res in responses) {
            assertEquals("nosniff", res.headers().firstValue("x-content-type-options").orElse(null), res.uri().toString())
            assertEquals("DENY", res.headers().firstValue("x-frame-options").orElse(null), res.uri().toString())
        }
    }
}
