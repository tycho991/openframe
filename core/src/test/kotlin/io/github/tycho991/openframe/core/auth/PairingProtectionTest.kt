package io.github.tycho991.openframe.core.auth

import io.github.tycho991.openframe.core.api.InviteDto
import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.http.HttpException
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.PairingHarness
import io.github.tycho991.openframe.core.testing.PairingHarness.Companion.secretOf
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Encoder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Spec `admin-access`: Code only while shown on the frame, Brute-force protection, Daily budget for pairing by code,
 * Pairing deadlines independent of the wall clock, Request code on the frame. Spec `slideshow-display`: Display of the
 * pairing QR code.
 */
class PairingProtectionTest {
    @TempDir
    lateinit var dir: File

    private val h by lazy { PairingHarness(dir) }
    private val pairing get() = h.pairing
    private val clock get() = h.clock

    @AfterEach
    fun tearDown() = h.close()

    private fun wrongFor(code: String) = if (code == "000000") "111111" else "000000"

    /** Five wrong codes against the code the frame shows; returns the `Retry-After` of the lockout. */
    private fun lockOnce(): Long {
        pairing.setShownOnFrame(true)
        val code = pairing.display.value.code!!
        repeat(PairingManager.MAX_FAILURES) { assertEquals(401, h.pairWithCode(wrongFor(code)).statusCode()) }
        val locked = h.pairWithCode(code)
        assertEquals(429, locked.statusCode(), locked.body())
        assertEquals("locked", h.errorCode(locked))
        return locked.header("retry-after")!!.toLong()
    }

    /** Waits out the lockout so that the frame shows a new code. */
    private fun waitOut(seconds: Long) {
        clock.advance(Duration.ofSeconds(seconds))
        pairing.tick()
    }

    // Code only while shown on the frame

    @Test
    fun `Display off`() {
        pairing.setScreenUsable(false)
        assertEquals(204, h.http.request("POST", "/api/v1/pairing/code").statusCode())
        // The activity shows the hint on the dark screen.
        pairing.setShownOnFrame(true)
        val shown = pairing.display.value
        assertNull(shown.code)
        assertTrue(shown.requested)
        assertNotNull(shown.url, "The secret of the QR code exists, it cannot be guessed")
        repeat(10) {
            clock.advance(Duration.ofSeconds(1))
            pairing.tick()
            assertNull(pairing.display.value.code)
            assertEquals(shown.url, pairing.display.value.url, "The secret stays the same")
        }
        // No code that a client could guess.
        for (guess in listOf("000000", "123456", "999999")) assertEquals("invalid_code", h.errorCode(h.pairWithCode(guess)))
    }

    @Test
    fun `Request while the display is off`() {
        pairing.setScreenUsable(false)
        assertEquals(204, h.http.request("POST", "/api/v1/pairing/code").statusCode())
        pairing.setShownOnFrame(true)
        assertNull(pairing.display.value.code)
        // The display comes on and shows the hint: now a code appears.
        pairing.setScreenUsable(true)
        val code = pairing.display.value.code
        assertNotNull(code)
        assertEquals(201, h.pairWithCode(code!!).statusCode())
    }

    @Test
    fun `A request alone creates no code`() {
        assertEquals(204, h.http.request("POST", "/api/v1/pairing/code").statusCode())
        assertTrue(pairing.display.value.requested)
        assertNull(pairing.display.value.code)
        assertNotNull(pairing.display.value.url)
    }

    @Test
    fun `Code entered after the hint was hidden`() {
        pairing.setShownOnFrame(true)
        val code = pairing.display.value.code!!
        clock.advance(Duration.ofMinutes(1))
        pairing.setShownOnFrame(false)
        assertNull(pairing.display.value.code)
        clock.advance(Duration.ofMinutes(3))
        pairing.tick()
        assertEquals(201, h.pairWithCode(code).statusCode())
    }

    // Brute-force protection

    @Test
    fun `Growing lockout`() {
        assertEquals(60, lockOnce())
        waitOut(60)
        assertEquals(120, lockOnce())
        waitOut(120)
        assertEquals(240, lockOnce())
    }

    @Test
    fun `Lockout back to 60 seconds`() {
        assertEquals(60, lockOnce())
        waitOut(60)
        assertEquals(120, lockOnce())
        waitOut(120)
        assertEquals(240, lockOnce())
        waitOut(240)
        pairing.setShownOnFrame(true)
        assertEquals(201, h.pairWithCode(pairing.display.value.code!!).statusCode())
        assertEquals(60, lockOnce())
    }

    @Test
    fun `Reset returns the lockout to 60 seconds`() {
        assertEquals(60, lockOnce())
        waitOut(60)
        assertEquals(120, lockOnce())
        pairing.reset()
        assertEquals(60, lockOnce())
    }

    @Test
    fun `No lockout lasts longer than an hour`() {
        val locks = mutableListOf<Long>()
        // Three lockouts fit into the budget of a day, the fourth round of wrong codes pauses pairing by code.
        repeat(4) {
            repeat(3) {
                val seconds = lockOnce()
                locks += seconds
                waitOut(seconds)
            }
            pairing.setShownOnFrame(true)
            val code = pairing.display.value.code!!
            repeat(PairingManager.MAX_FAILURES) { h.pairWithCode(wrongFor(code)) }
            assertEquals("code_paused", h.errorCode(h.pairWithCode(code)))
            waitOut(Duration.ofHours(25).seconds)
        }
        assertEquals(listOf(60L, 120, 240, 480, 960, 1920, 3600, 3600, 3600, 3600, 3600, 3600), locks)
    }

    // Daily budget for pairing by code

    @Test
    fun `Budget used up`() {
        assertEquals(60, lockOnce())
        waitOut(60)
        assertEquals(120, lockOnce())
        waitOut(120)
        assertEquals(240, lockOnce())
        waitOut(240)
        pairing.setShownOnFrame(true)
        val code = pairing.display.value.code!!
        repeat(PairingManager.MAX_FAILURES) { assertEquals(401, h.pairWithCode(wrongFor(code)).statusCode()) }
        // 20 wrong codes in 7 minutes: paused until the first of them is 24 hours old.
        val paused = h.pairWithCode(code)
        assertEquals(429, paused.statusCode())
        assertEquals("code_paused", h.errorCode(paused))
        val retry = paused.header("retry-after")!!.toLong()
        assertEquals(Duration.ofHours(24).minusSeconds(60 + 120 + 240).seconds, retry)
        // The frame shows the QR code without a code.
        val shown = pairing.display.value
        assertNull(shown.code)
        assertTrue(shown.codeLocked)
        assertNotNull(shown.url)
        // Still paused shortly before the end, a code again afterwards.
        waitOut(retry - 1)
        assertEquals("code_paused", h.errorCode(h.pairWithCode(code)))
        waitOut(1)
        assertFalse(pairing.display.value.codeLocked)
        assertEquals(201, h.pairWithCode(pairing.display.value.code!!).statusCode())
    }

    @Test
    fun `QR code while paused`() {
        repeat(3) { waitOut(lockOnce()) }
        pairing.setShownOnFrame(true)
        val code = pairing.display.value.code!!
        repeat(PairingManager.MAX_FAILURES) { h.pairWithCode(wrongFor(code)) }
        assertEquals("code_paused", h.errorCode(h.pairWithCode(code)))
        assertEquals(201, h.pairWithSecret(secretOf(pairing.display.value.url!!)).statusCode())
        // Pairing by code works again afterwards.
        assertFalse(pairing.display.value.codeLocked)
        assertEquals(201, h.pairWithCode(pairing.display.value.code!!).statusCode())
    }

    @Test
    fun `At most 20 guesses in 24 hours`() {
        pairing.setShownOnFrame(true)
        var guesses = 0
        val end = clock.monotonic + Duration.ofHours(24).toMillis()
        while (clock.monotonic < end) {
            try {
                pairing.pair(wrongFor(pairing.display.value.code ?: "000000"), "Attacker")
            } catch (e: HttpException) {
                if (e.code == "invalid_code") guesses++
            }
            clock.advance(Duration.ofSeconds(5))
            pairing.tick()
        }
        assertEquals(PairingManager.DAILY_BUDGET, guesses)
    }

    // Pairing deadlines independent of the wall clock

    @Test
    fun `Clock set back`() {
        assertEquals(60, lockOnce())
        clock.instant = clock.instant - Duration.ofDays(1)
        clock.advance(Duration.ofSeconds(30))
        val locked = h.pairWithCode("123456")
        assertEquals(429, locked.statusCode())
        assertEquals("30", locked.header("retry-after"))
        waitOut(30)
        assertFalse(pairing.display.value.codeLocked)
        assertEquals(201, h.pairWithCode(pairing.display.value.code!!).statusCode())
    }

    @Test
    fun `Clock set forward`() {
        val auth = h.pairedAuth("Phone")
        val res = h.http.request("POST", "/api/v1/pairing/invites", headers = auth)
        val invite = ApiJson.decodeFromString(InviteDto.serializer(), res.body())
        assertEquals(clock.now() + PairingManager.VALIDITY, Instant.parse(invite.expiresAt))
        clock.instant = clock.instant + Duration.ofDays(1)
        clock.advance(Duration.ofMinutes(4))
        pairing.tick()
        assertEquals(201, h.pairWithSecret(secretOf(invite.url)).statusCode())
    }

    @Test
    fun `A jump of the clock during a session and a request window`() {
        assertEquals(204, h.http.request("POST", "/api/v1/pairing/code").statusCode())
        pairing.setShownOnFrame(true)
        val code = pairing.display.value.code!!
        clock.instant = clock.instant + Duration.ofDays(1)
        pairing.tick()
        assertTrue(pairing.display.value.requested)
        assertEquals(code, pairing.display.value.code)
        clock.instant = clock.instant - Duration.ofDays(2)
        clock.advance(Duration.ofMinutes(4))
        pairing.tick()
        assertTrue(pairing.display.value.requested)
        assertEquals(code, pairing.display.value.code)
        clock.advance(Duration.ofMinutes(1))
        pairing.tick()
        assertFalse(pairing.display.value.requested)
        assertEquals("expired_code", h.errorCode(h.pairWithCode(code)))
    }

    // Creating the client outside the lock

    @Test
    fun `Creating the client does not hold the pairing lock`() {
        val serializer = BlockingSerializer(StoredClients.serializer())
        val registry = ClientRegistry(JsonStore(File(dir, "blocking.json"), serializer, TestScope(), h.log), clock)
        val manager = PairingManager(registry, clock, h.log, { PairingHarness.ADMIN_URL })
        manager.setShownOnFrame(true)
        val code = manager.display.value.code!!
        serializer.blocking = true
        var paired: StoredClient? = null
        val pairingThread = thread { paired = manager.pair(code, "Phone").second }
        try {
            assertTrue(serializer.entered.await(5, TimeUnit.SECONDS), "create() writes")
            val other =
                thread {
                    manager.setShownOnFrame(false)
                    manager.tick()
                    manager.requestDisplay()
                }
            other.join(2000)
            assertFalse(other.isAlive, "The pairing lock is free while the client is stored")
        } finally {
            serializer.release.countDown()
        }
        pairingThread.join(5000)
        assertEquals("Phone", paired?.name)
        assertEquals(listOf("Phone"), registry.clients.value.map { it.name })
    }

    /** Blocks the first write once [blocking] is set, until [release]. */
    private class BlockingSerializer<T>(
        private val inner: KSerializer<T>,
    ) : KSerializer<T> by inner {
        @Volatile var blocking = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun serialize(
            encoder: Encoder,
            value: T,
        ) {
            if (blocking) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            inner.serialize(encoder, value)
        }
    }
}
