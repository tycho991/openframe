package io.github.tycho991.openframe.core.auth

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.test.TestScope
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
        val signature = signer.sign("p1")
        val oldCode = pairing.display.value.code
        // Directories in the place of the temp files make every atomic write fail.
        File(dir, "clients.json.tmp").mkdir()
        File(dir, "secret.json.tmp").mkdir()

        PairingAdmin(clients, signer, pairing, log).resetAll()

        assertNull(clients.authenticate(phone))
        assertNull(clients.authenticate(laptop))
        assertFalse(signer.verify("p1", signature))
        assertNotNull(pairing.display.value.code)
        assertNotEquals(oldCode, pairing.display.value.code)
        assertTrue(log.recent(100).any { it.message == "All pairings revoked on the frame" })

        // After a restart no earlier token or image signature is valid.
        val restarted = clients()
        assertNull(restarted.authenticate(phone))
        assertNull(restarted.authenticate(laptop))
        assertFalse(signer().verify("p1", signature))
    }

    @Test
    fun `Reset with an image key that cannot be stored anywhere`() {
        val clients = clients()
        val signer = signer()
        val pairing = PairingManager(clients, clock, log, { "http://192.168.100.215:8080/" })
        val (phone, _) = clients.create("Phone")
        val signature = signer.sign("p1")
        // A file in the place of the state folder: nothing can be written there.
        val blocked = File(dir, "blocked").apply { writeText("not a folder") }
        val brokenSigner = ImageSigner(JsonStore(File(blocked, "secret.json"), StoredSecret.serializer(), TestScope(), log), clock)

        PairingAdmin(clients, brokenSigner, pairing, log).resetAll()

        assertNull(clients.authenticate(phone))
        assertTrue(signer.verify("p1", signature), "the other signer is untouched")
        assertNotNull(pairing.display.value.code)
    }
}
