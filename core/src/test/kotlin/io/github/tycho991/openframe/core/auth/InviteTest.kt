package io.github.tycho991.openframe.core.auth

import io.github.tycho991.openframe.core.api.InviteDto
import io.github.tycho991.openframe.core.api.adminUrlFromHost
import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.qr.QrMatrix
import io.github.tycho991.openframe.core.testing.PairingHarness
import io.github.tycho991.openframe.core.testing.PairingHarness.Companion.ADMIN_URL
import io.github.tycho991.openframe.core.testing.PairingHarness.Companion.secretOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant

/** Spec `admin-access`: Pairing by invitation, Invitation address, Invitation ends with the inviting device. */
class InviteTest {
    @TempDir
    lateinit var dir: File

    private val h by lazy { PairingHarness(dir) }

    @AfterEach
    fun tearDown() = h.close()

    private fun invite(
        auth: Map<String, String>,
        host: String = "192.168.100.215:8080",
    ): InviteDto {
        val res = h.http.request("POST", "/api/v1/pairing/invites", headers = auth + ("Host" to host))
        assertEquals(201, res.statusCode(), res.body())
        return ApiJson.decodeFromString(InviteDto.serializer(), res.body())
    }

    @Test
    fun `Invite a second phone`() {
        val phone = h.pairedAuth("Phone")
        val invite = invite(phone)
        assertTrue(invite.url.startsWith("http://192.168.100.215:8080/#pair="), invite.url)
        assertEquals(h.clock.now() + Duration.ofMinutes(5), Instant.parse(invite.expiresAt))
        assertEquals(QrMatrix.encode(invite.url).rows(), invite.qr)
        assertEquals(201, h.pairWithSecret(secretOf(invite.url)).statusCode())
        assertEquals(2, h.clients.clients.value.size)
        // Only once.
        assertEquals(401, h.pairWithSecret(secretOf(invite.url)).statusCode())
    }

    @Test
    fun `New invitation replaces the old one`() {
        val phone = h.pairedAuth("Phone")
        val first = invite(phone)
        val second = invite(phone)
        assertEquals("invalid_code", h.errorCode(h.pairWithSecret(secretOf(first.url))))
        assertEquals(201, h.pairWithSecret(secretOf(second.url)).statusCode())
    }

    @Test
    fun `Invitations of two devices are independent`() {
        val a = invite(h.pairedAuth("Phone"))
        val b = invite(h.pairedAuth("Laptop"))
        assertEquals(201, h.pairWithSecret(secretOf(a.url)).statusCode())
        assertEquals(201, h.pairWithSecret(secretOf(b.url)).statusCode())
    }

    @Test
    fun `Same origin`() {
        val phone = h.pairedAuth("Phone")
        assertTrue(invite(phone, "frame.home.arpa:8080").url.startsWith("http://frame.home.arpa:8080/#pair="))
        assertTrue(invite(phone, "[fe80::1]:8080").url.startsWith("http://[fe80::1]:8080/#pair="))
        assertTrue(invite(phone, "192.168.100.215").url.startsWith("http://192.168.100.215/#pair="))
    }

    @Test
    fun `Invalid Host falls back to the frame's address`() {
        val phone = h.pairedAuth("Phone")
        assertTrue(invite(phone, "bad_host!:8080").url.startsWith("$ADMIN_URL#pair="))
        for (host in listOf(null, "", "a b", "host:99999", "host:", "evil.com/x", "[fe80::1", "user@host", "-host")) {
            assertNull(adminUrlFromHost(host), "$host")
        }
        assertEquals("http://Frame-1.local:80/", adminUrlFromHost("Frame-1.local:80"))
    }

    @Test
    fun `Inviting device revoked`() {
        val phone = h.pairedAuth("Phone")
        val laptop = h.pairedAuth("Laptop")
        val invite = invite(phone)
        val phoneId =
            h.clients.clients.value
                .first { it.name == "Phone" }
                .id
        assertEquals(204, h.http.request("DELETE", "/api/v1/clients/$phoneId", headers = laptop).statusCode())
        assertEquals(401, h.pairWithSecret(secretOf(invite.url)).statusCode())
    }

    @Test
    fun `Invitation expires after 5 minutes`() {
        val invite = invite(h.pairedAuth("Phone"))
        h.clock.advance(Duration.ofMinutes(5))
        val res = h.pairWithSecret(secretOf(invite.url))
        assertEquals(401, res.statusCode())
        assertEquals("expired_code", h.errorCode(res))
    }

    @Test
    fun `Lockout does not affect invitations`() {
        val invite = invite(h.pairedAuth("Phone"))
        h.pairing.requestDisplay()
        val code = h.pairing.display.value.code!!
        val wrong = if (code == "000000") "111111" else "000000"
        repeat(PairingManager.MAX_FAILURES) { h.pairWithCode(wrong) }
        assertEquals(429, h.pairWithCode(code).statusCode())
        assertEquals(201, h.pairWithSecret(secretOf(invite.url)).statusCode())
    }

    @Test
    fun `Without token no invitation`() {
        assertEquals(401, h.http.request("POST", "/api/v1/pairing/invites").statusCode())
    }
}
