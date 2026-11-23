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

    /** Spec `admin-access`, "Origin of a pairing" › Pairing from before the update. */
    @Test
    fun `A clients json without the origin loads without one`() {
        File(dir, "clients.json").writeText(
            """{"clients":[{"id":"0123456789abcdef","name":"Phone","tokenHash":"${ClientRegistry.hash(
                "t",
            )}","pairedAtMs":1,"lastSeenAtMs":2}]}""",
        )
        val client = registry().clients.value.single()
        assertEquals("Phone", client.name)
        assertNull(client.pairedVia)
        assertNull(client.invitedBy)
        assertEquals(client.id, registry().authenticate("t")?.id)
    }

    /** Spec `admin-access`, "Origin of a pairing" › Origin from a newer version. */
    @Test
    fun `An origin this version does not know keeps every pairing and is served as none`() {
        val hash = ClientRegistry.hash("t")
        File(dir, "clients.json").writeText(
            """{"clients":[""" +
                """{"id":"0123456789abcdef","name":"Phone","tokenHash":"$hash","pairedAtMs":1,"lastSeenAtMs":2,"pairedVia":"nfc"},""" +
                """{"id":"fedcba9876543210","name":"Tablet","tokenHash":"${ClientRegistry.hash("u")}","pairedAtMs":1,"lastSeenAtMs":2,""" +
                """"pairedVia":"invite","invitedBy":"0123456789abcdef"}]}""",
        )
        val clients = registry().clients.value
        assertEquals(listOf("Phone", "Tablet"), clients.map { it.name })
        assertEquals("nfc", clients[0].pairedVia, "kept as stored")
        assertNull(PairedVia.served(clients[0].pairedVia))
        assertEquals("invite", PairedVia.served(clients[1].pairedVia))
        assertFalse(dir.listFiles().orEmpty().any { it.name.contains("broken") }, "the file was not moved aside")
    }
}
