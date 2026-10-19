package io.github.tycho991.openframe.core.auth

import io.github.tycho991.openframe.core.testing.PairingHarness
import io.github.tycho991.openframe.core.testing.PairingHarness.Companion.ADMIN_URL
import io.github.tycho991.openframe.core.testing.PairingHarness.Companion.secretOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration

/** Spec `admin-access`: Pairing by QR code, Brute-force protection, Request code on the frame. */
class QrPairingTest {
    @TempDir
    lateinit var dir: File

    private val h by lazy { PairingHarness(dir) }

    @AfterEach
    fun tearDown() = h.close()

    private fun lockCode() {
        val code = h.pairing.display.value.code!!
        val wrong = if (code == "000000") "111111" else "000000"
        repeat(PairingManager.MAX_FAILURES) { assertEquals(401, h.pairWithCode(wrong).statusCode()) }
        assertEquals(429, h.pairWithCode(code).statusCode())
    }

    @Test
    fun `Scan with the phone`() {
        h.pairing.setShownOnFrame(true)
        val url = h.pairing.display.value.url!!
        assertTrue(url.startsWith("$ADMIN_URL#pair="), url)
        assertTrue(secretOf(url).matches(Regex("[A-Za-z0-9_-]{22}")))
        val res = h.pairWithSecret(secretOf(url))
        assertEquals(201, res.statusCode())
        // The name comes from the user agent.
        assertEquals(1, h.clients.clients.value.size)
        // The frame still shows pairing and gets a new session.
        assertNotEquals(url, h.pairing.display.value.url)
    }

    @Test
    fun `QR code used twice`() {
        h.pairing.setShownOnFrame(true)
        val url = h.pairing.display.value.url!!
        assertEquals(201, h.pairWithSecret(secretOf(url)).statusCode())
        val again = h.pairWithSecret(secretOf(url))
        assertEquals(401, again.statusCode())
        h.pairing.requestDisplay()
        assertNotEquals(url, h.pairing.display.value.url)
    }

    @Test
    fun `Code after a scan`() {
        h.pairing.setShownOnFrame(true)
        val shown = h.pairing.display.value
        assertEquals(201, h.pairWithSecret(secretOf(shown.url!!)).statusCode())
        assertEquals(401, h.pairWithCode(shown.code!!).statusCode())
    }

    @Test
    fun `Secret after the code`() {
        h.pairing.setShownOnFrame(true)
        val shown = h.pairing.display.value
        assertEquals(201, h.pairWithCode(shown.code!!).statusCode())
        assertEquals(401, h.pairWithSecret(secretOf(shown.url!!)).statusCode())
    }

    @Test
    fun `QR code during the lockout`() {
        h.pairing.setShownOnFrame(true)
        val url = h.pairing.display.value.url!!
        lockCode()
        val shown = h.pairing.display.value
        assertNull(shown.code)
        assertTrue(shown.codeLocked)
        assertEquals(url, shown.url, "The QR code stays during the lockout")
        assertEquals(201, h.pairWithSecret(secretOf(url)).statusCode())
    }

    @Test
    fun `Wrong secrets`() {
        h.pairing.setShownOnFrame(true)
        val code = h.pairing.display.value.code!!
        repeat(10) {
            val res = h.pairWithSecret("A".repeat(22))
            assertEquals(401, res.statusCode())
            assertEquals("invalid_code", h.errorCode(res))
        }
        assertFalse(h.pairing.display.value.codeLocked)
        assertEquals(201, h.pairWithCode(code).statusCode())
    }

    @Test
    fun `Request during the lockout`() {
        h.pairing.requestDisplay()
        lockCode()
        assertEquals(204, h.http.request("POST", "/api/v1/pairing/code").statusCode())
        val locked = h.pairing.display.value
        assertNull(locked.code)
        assertTrue(locked.codeLocked)
        assertNotNull(locked.url)
        // After the lockout a new session with code and QR code replaces the old one.
        h.clock.advance(PairingManager.LOCK)
        h.pairing.tick()
        val after = h.pairing.display.value
        assertFalse(after.codeLocked)
        assertNotNull(after.code)
        assertNotEquals(locked.url, after.url)
        assertEquals("expired_code", h.errorCode(h.pairWithSecret(secretOf(locked.url!!))))
    }

    @Test
    fun `Session expires during the lockout`() {
        h.pairing.setShownOnFrame(true)
        h.clock.advance(Duration.ofMinutes(4).plusSeconds(30))
        lockCode()
        val first = h.pairing.display.value.url!!
        h.clock.advance(Duration.ofSeconds(30))
        h.pairing.tick()
        val during = h.pairing.display.value
        assertTrue(during.codeLocked)
        assertNull(during.code)
        assertNotEquals(first, during.url, "A new secret without a code")
        assertEquals(201, h.pairWithSecret(secretOf(during.url!!)).statusCode())
    }

    @Test
    fun `Session is replaced after 5 minutes`() {
        h.pairing.setShownOnFrame(true)
        val first = h.pairing.display.value
        h.clock.advance(PairingManager.VALIDITY)
        h.pairing.tick()
        val second = h.pairing.display.value
        assertNotEquals(first.url, second.url)
        assertNotEquals(first.code, second.code)
        assertEquals("expired_code", h.errorCode(h.pairWithSecret(secretOf(first.url!!))))
        assertEquals(201, h.pairWithSecret(secretOf(second.url!!)).statusCode())
    }

    @Test
    fun `No QR code without a network address`() {
        val pairing = PairingManager(h.clients, h.clock, h.log, { null })
        pairing.setShownOnFrame(true)
        assertNotNull(pairing.display.value.code)
        assertNull(pairing.display.value.url)
    }
}
