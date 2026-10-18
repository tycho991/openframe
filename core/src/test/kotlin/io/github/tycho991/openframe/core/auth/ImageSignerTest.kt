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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant

/** Spec `admin-access`: Signierte Bild-URL, Signatur auf anderes Foto übertragen. */
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

    @Test
    fun `Gueltige Signatur`() {
        val s = signer()
        assertTrue(s.verify(a, s.sign(a)))
    }

    @Test
    fun `Signatur auf anderes Foto uebertragen`() {
        val s = signer()
        assertFalse(s.verify(b, s.sign(a)))
    }

    @Test
    fun `Ablauf zur uebernaechsten vollen Stunde`() {
        val s = signer()
        val t = s.sign(a)
        assertEquals(Instant.parse("2026-05-01T12:00:00Z").epochSecond, t.substringBefore('.').toLong())
        clock.advance(Duration.ofMinutes(104))
        assertTrue(s.verify(a, t), "11:59:30 noch gültig")
        clock.advance(Duration.ofSeconds(30))
        assertFalse(s.verify(a, t), "12:00:00 abgelaufen")
    }

    @Test
    fun `Gleiche URL innerhalb einer Stunde`() {
        val s = signer()
        val first = s.sign(a)
        clock.instant = Instant.parse("2026-05-01T10:59:59Z")
        assertEquals(first, s.sign(a))
        clock.instant = Instant.parse("2026-05-01T11:00:00Z")
        assertNotEquals(first, s.sign(a))
    }

    @Test
    fun `Manipulierte Signaturen`() {
        val s = signer()
        val t = s.sign(a)
        val later = (t.substringBefore('.').toLong() + 3600).toString() + "." + t.substringAfter('.')
        assertFalse(s.verify(a, later), "verlängerter Ablauf")
        assertFalse(s.verify(a, t.dropLast(1) + if (t.last() == 'A') "B" else "A"))
        assertFalse(s.verify(a, ""))
        assertFalse(s.verify(a, "kaputt"))
        val far = (clock.now().epochSecond + 3 * 3600).toString() + ".x"
        assertFalse(s.verify(a, far), "zu weit in der Zukunft")
    }

    @Test
    fun `Schluessel ueberlebt Neustart, Rotation macht alte Signaturen ungueltig`() {
        val t = signer().sign(a)
        assertTrue(signer().verify(a, t))
        val s = signer()
        s.rotate()
        assertFalse(s.verify(a, t))
        assertFalse(signer().verify(a, t))
    }

    @Test
    fun `Signatur als Alternative zum Token nur fuer Bildabrufe`() {
        val s = signer()
        val clients = ClientRegistry(JsonStore(File(dir, "clients.json"), StoredClients.serializer(), TestScope(), log), clock)
        val router =
            Router().apply {
                get("/api/v1/photos/{photoId}/image", Access.TOKEN_OR_IMAGE_SIGNATURE) { HttpResponse.noContent() }
                get("/api/v1/photos/{photoId}") { HttpResponse.noContent() }
            }
        TestHttp(HttpPipeline(router, AccessControl(clients, s), log)::handle).use { http ->
            val t = s.sign(a)
            assertEquals(204, http.get("/api/v1/photos/$a/image?size=thumb&v=1&t=$t").statusCode())
            assertEquals(401, http.get("/api/v1/photos/$b/image?size=thumb&v=1&t=$t").statusCode())
            assertEquals(401, http.get("/api/v1/photos/$a/image?size=thumb").statusCode())
            assertEquals(401, http.get("/api/v1/photos/$a?t=$t").statusCode(), "Signatur gilt nicht für andere Endpunkte")
        }
    }
}
