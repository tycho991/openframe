package io.github.tycho991.openframe.core.auth

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `admin-access`: Reset all pairings on the frame. */
class PairingAdminTest {
    @TempDir
    lateinit var dir: File

    private val clock = TestClock()
    private val log = AppLog(clock)

    private fun clients() =
        ClientRegistry(JsonStore(File(dir, "clients.json"), StoredClients.serializer(), TestScope(), log), clock).also { it.load() }

    private fun signer() =
        ImageSigner(JsonStore(File(dir, "secret.json"), StoredSecret.serializer(), TestScope(), log), clock).also {
            it.load()
        }

    @Test
    fun `Reset with full storage`() {
        val clients = clients()
        val signer = signer()
        val pairing = PairingManager(clients, clock, log, { "http://192.168.100.215:8080/" })
        val (phone, _) = clients.create("Phone")
        val (laptop, _) = clients.create("Laptop")
        val signature = signer.sign("p1", "0123456789abcdef")
        val oldCode = pairing.display.value.code
        // Directories in the place of the temp files make every atomic write fail.
        File(dir, "clients.json.tmp").mkdir()
        File(dir, "secret.json.tmp").mkdir()

        PairingAdmin(clients, signer, pairing, log).resetAll()

        assertNull(clients.authenticate(phone))
        assertNull(clients.authenticate(laptop))
        assertNull(signer.verify("p1", signature))
        // The reset asks the frame to show the hint, and the activity shows it.
        assertTrue(pairing.display.value.requested)
        pairing.setShownOnFrame(true)
        assertNotNull(pairing.display.value.code)
        assertNotEquals(oldCode, pairing.display.value.code)
        assertTrue(log.recent(100).any { it.message == "All pairings revoked on the frame" })

        // After a restart no earlier token or image signature is valid.
        val restarted = clients()
        assertNull(restarted.authenticate(phone))
        assertNull(restarted.authenticate(laptop))
        assertNull(signer().verify("p1", signature))
    }

    @Test
    fun `Reset with an image key that cannot be stored anywhere`() {
        val clients = clients()
        val signer = signer()
        val pairing = PairingManager(clients, clock, log, { "http://192.168.100.215:8080/" })
        val (phone, _) = clients.create("Phone")
        val signature = signer.sign("p1", "0123456789abcdef")
        // A file in the place of the state folder: nothing can be written there.
        val blocked = File(dir, "blocked").apply { writeText("not a folder") }
        val brokenSigner = ImageSigner(JsonStore(File(blocked, "secret.json"), StoredSecret.serializer(), TestScope(), log), clock)

        PairingAdmin(clients, brokenSigner, pairing, log).resetAll()

        assertNull(clients.authenticate(phone))
        assertEquals("0123456789abcdef", signer.verify("p1", signature), "the other signer is untouched")
        assertTrue(pairing.display.value.requested)
        pairing.setShownOnFrame(true)
        assertNotNull(pairing.display.value.code)
    }

    /** Spec `admin-access`, "Equal rights of paired devices" › A hostile device keeps revoking. */
    @Test
    fun `Unpair all devices ends a device that revokes every new pairing`() {
        val clients = clients()
        val signer = signer()
        val pairing = PairingManager(clients, clock, log, { "http://192.168.100.215:8080/" })
        val (hostileToken, hostile) = clients.create("Visitor", PairedVia.INVITE, "0123456789abcdef")
        // Every new pairing is revoked by the hostile device as soon as it appears.
        val (_, victim) = clients.create("Phone", PairedVia.CODE, null)
        assertNotNull(clients.authenticate(hostileToken))
        assertTrue(clients.remove(victim.id))

        PairingAdmin(clients, signer, pairing, log).resetAll()

        assertNull(clients.authenticate(hostileToken), "the hostile token is gone as well")
        assertFalse(clients.clients.value.any { it.id == hostile.id })
        pairing.setShownOnFrame(true)
        val (token, _) = pairing.pair(pairing.display.value.code!!, "Phone")
        assertNotNull(clients.authenticate(token), "the next pairing stays")
        assertNotNull(clients().authenticate(token), "also stored")
    }
}
