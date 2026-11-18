package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.testing.TestFrame
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `admin-access`: Host check against DNS rebinding, with the contract check on. */
class HostCheckTest {
    @TempDir
    lateinit var dir: File

    private val frame by lazy { TestFrame(dir) }

    @AfterEach
    fun tearDown() = frame.close()

    private val rebind = mapOf("Host" to "rebind.example:8080", "Origin" to "http://rebind.example:8080")

    @Test
    fun `Rebinding page`() {
        val responses =
            listOf(
                frame.http.request("GET", "/api/v1/info", headers = rebind),
                frame.http.request("POST", "/api/v1/pairing", """{"code":"123456"}""", headers = rebind),
                frame.http.request("POST", "/api/v1/pairing/code", headers = rebind),
                frame.http.request("GET", "/api/v1/settings", headers = rebind + frame.auth),
                frame.http.request("GET", "/", headers = rebind),
            )
        for (res in responses) {
            assertEquals(421, res.statusCode(), res.uri().toString())
            assertTrue("\"misdirected_host\"" in res.body(), res.body())
            assertEquals("nosniff", res.header("x-content-type-options"))
            assertEquals("DENY", res.header("x-frame-options"))
        }
        assertFalse("\"name\"" in responses[0].body(), "No info")
        assertFalse(frame.core.pairing.display.value.requested, "No code requested on the frame")
    }

    @Test
    fun `Local names`() {
        for (host in listOf("192.168.100.215:8080", "frame.local:8080", "frame.fritz.box:8080", "[fe80::1]:8080", "localhost:8080")) {
            val headers = mapOf("Host" to host, "Origin" to "http://$host")
            assertEquals(200, frame.http.request("GET", "/api/v1/info", headers = headers).statusCode(), host)
            assertEquals(200, frame.http.request("GET", "/api/v1/settings", headers = headers + frame.auth).statusCode(), host)
            assertEquals(204, frame.http.request("POST", "/api/v1/pairing/code", headers = headers).statusCode(), host)
        }
    }

    @Test
    fun `A request without Host`() {
        val res = frame.http.raw("GET /api/v1/info HTTP/1.0\r\n\r\n")
        assertEquals(200, res.status, res.body)
    }
}
