package io.github.tycho991.openframe.core.auth

import io.github.tycho991.openframe.core.http.Access
import io.github.tycho991.openframe.core.http.HttpPipeline
import io.github.tycho991.openframe.core.http.HttpResponse
import io.github.tycho991.openframe.core.http.Router
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.TestClock
import io.github.tycho991.openframe.core.testing.TestHttp
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant

/** Spec `admin-access`: Signed image URL, signature reused for another photo. */
class ImageSignerTest {
    @TempDir
    lateinit var dir: File

    private val clock = TestClock(Instant.parse("2026-05-01T10:15:30Z"))
    private val log = AppLog(clock)

    private fun signer() =
        ImageSigner(JsonStore(File(dir, "secret.json"), StoredSecret.serializer(), TestScope(), log), clock).also {
            it.load()
        }

    private val a = "local-" + "a".repeat(40)
    private val b = "local-" + "b".repeat(40)
    private val c = "0123456789abcdef"

    @Test
    fun `Valid signature`() {
        val s = signer()
        assertEquals(c, s.verify(a, s.sign(a, c)))
    }

    @Test
    fun `Signature reused for another photo`() {
        val s = signer()
        assertNull(s.verify(b, s.sign(a, c)))
    }

    @Test
    fun `Expiry at the full hour after next`() {
        val s = signer()
        val t = s.sign(a, c)
        assertEquals(Instant.parse("2026-05-01T12:00:00Z").epochSecond, t.substringBefore('.').toLong())
        clock.advance(Duration.ofMinutes(104))
        assertEquals(c, s.verify(a, t), "11:59:30 still valid")
        clock.advance(Duration.ofSeconds(30))
        assertNull(s.verify(a, t), "12:00:00 expired")
    }

    @Test
    fun `Same URL within an hour`() {
        val s = signer()
        val first = s.sign(a, c)
        clock.instant = Instant.parse("2026-05-01T10:59:59Z")
        assertEquals(first, s.sign(a, c))
        clock.instant = Instant.parse("2026-05-01T11:00:00Z")
        assertNotEquals(first, s.sign(a, c))
    }

    @Test
    fun `Tampered signatures`() {
        val s = signer()
        val t = s.sign(a, c)
        val later = (t.substringBefore('.').toLong() + 3600).toString() + "." + t.substringAfter('.')
        assertNull(s.verify(a, later), "extended expiry")
        assertNull(s.verify(a, t.dropLast(1) + if (t.last() == 'A') "B" else "A"))
        assertNull(s.verify(a, ""))
        assertNull(s.verify(a, "broken"))
        val far = (clock.now().epochSecond + 3 * 3600).toString() + ".x"
        assertNull(s.verify(a, far), "too far in the future")
    }

    @Test
    fun `Key survives restart, rotation invalidates old signatures`() {
        val t = signer().sign(a, c)
        assertEquals(c, signer().verify(a, t))
        val s = signer()
        s.rotate()
        assertNull(s.verify(a, t))
        assertNull(signer().verify(a, t))
    }

    @Test
    fun `Signature as an alternative to the token for image requests only`() {
        val s = signer()
        val clients = ClientRegistry(JsonStore(File(dir, "clients.json"), StoredClients.serializer(), TestScope(), log), clock)
        val router =
            Router().apply {
                get("/api/v1/photos/{photoId}/image", Access.TOKEN_OR_IMAGE_SIGNATURE) { HttpResponse.noContent() }
                get("/api/v1/photos/{photoId}") { HttpResponse.noContent() }
            }
        val phone = clients.create("Phone").second
        TestHttp(HttpPipeline(router, AccessControl(clients, s), log)::handle).use { http ->
            val t = s.sign(a, phone.id)
            assertEquals(204, http.get("/api/v1/photos/$a/image?size=thumb&v=1&t=$t").statusCode())
            assertEquals(401, http.get("/api/v1/photos/$b/image?size=thumb&v=1&t=$t").statusCode())
            assertEquals(401, http.get("/api/v1/photos/$a/image?size=thumb").statusCode())
            assertEquals(401, http.get("/api/v1/photos/$a?t=$t").statusCode(), "Signature does not apply to other endpoints")
            // Signed for a client that was never paired, or that is removed.
            assertEquals(401, http.get("/api/v1/photos/$a/image?size=thumb&v=1&t=${s.sign(a, c)}").statusCode())
            clients.remove(phone.id)
            assertEquals(401, http.get("/api/v1/photos/$a/image?size=thumb&v=1&t=$t").statusCode())
        }
    }

    @Test
    fun `Signature with another client ID`() {
        val s = signer()
        val t = s.sign(a, c)
        val parts = t.split('.')
        assertEquals(c, parts[1])
        val swapped = "${parts[0]}.fedcba9876543210.${parts[2]}"
        assertNull(s.verify(a, swapped))
        assertNull(s.verify(a, "${parts[0]}.${parts[2]}"), "the old format")
        assertNull(s.verify(b, t))
        assertNotEquals(t, s.sign(a, "fedcba9876543210"), "each client its own URL")
    }
}
