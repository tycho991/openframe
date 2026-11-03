package io.github.tycho991.openframe.core.auth

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/** What the registry does when the storage fails: a token is delivered only for a client that is stored. */
class ClientRegistryPersistenceTest {
    @TempDir
    lateinit var dir: File

    private val clock = TestClock()
    private val scope = TestScope()

    private fun registry() =
        ClientRegistry(JsonStore(File(dir, "clients.json"), StoredClients.serializer(), scope, AppLog(clock)), clock).also { it.load() }

    /** A directory in the place of the temp file makes every write fail. */
    private fun breakStorage() = File(dir, "clients.json.tmp").apply { mkdir() }

    @Test
    fun `Client is not published when storing fails`() {
        val registry = registry()
        val blocker = breakStorage()
        assertThrows(IOException::class.java) { registry.create("Phone") }
        assertTrue(registry.clients.value.isEmpty())
        blocker.delete()
        val (token, client) = registry.create("Phone")
        assertEquals(client, registry.authenticate(token))
        assertEquals(listOf(client), registry().clients.value, "also stored")
    }

    @Test
    fun `Rename is not published when storing fails`() {
        val registry = registry()
        val (_, client) = registry.create("Phone")
        val blocker = breakStorage()
        assertThrows(IOException::class.java) { registry.rename(client.id, "Tablet") }
        assertEquals(
            "Phone",
            registry.clients.value
                .single()
                .name,
        )
        blocker.delete()
        assertEquals("Tablet", registry.rename(client.id, "Tablet")!!.name)
    }

    /** Spec `admin-access`: Manage tokens › Revoke with full storage. */
    @Test
    fun `Revocation holds even if storing fails`() {
        val registry = registry()
        val (token, client) = registry.create("Phone")
        val (otherToken, other) = registry.create("Laptop")
        breakStorage()
        assertTrue(registry.remove(client.id))
        assertNull(registry.authenticate(token))

        // Written in place: after a restart the revoked token is invalid and the other one valid.
        val restarted = registry()
        assertNull(restarted.authenticate(token))
        assertEquals(other.id, restarted.authenticate(otherToken)?.id)

        registry.removeAll()
        assertTrue(registry.clients.value.isEmpty())
        assertTrue(registry().clients.value.isEmpty())
    }

    @Test
    fun `Revocation on a storage that cannot be written at all deletes the list`() {
        assumeFalse(System.getProperty("user.name") == "root", "root writes a read-only file")
        val registry = registry()
        val (token, client) = registry.create("Phone")
        val (otherToken, _) = registry.create("Laptop")
        breakStorage()
        File(dir, "clients.json").setWritable(false)
        assertTrue(registry.remove(client.id))

        assertFalse(File(dir, "clients.json").exists(), "fail-closed: every device pairs again")
        val restarted = registry()
        assertNull(restarted.authenticate(token))
        assertNull(restarted.authenticate(otherToken))
    }
}
