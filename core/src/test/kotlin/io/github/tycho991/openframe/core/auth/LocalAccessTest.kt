package io.github.tycho991.openframe.core.auth

import io.github.tycho991.openframe.core.http.Access
import io.github.tycho991.openframe.core.http.FrameServer
import io.github.tycho991.openframe.core.http.HttpException
import io.github.tycho991.openframe.core.http.HttpPipeline
import io.github.tycho991.openframe.core.http.HttpResponse
import io.github.tycho991.openframe.core.http.Router
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** Spec `admin-access`: Reset all pairings on the frame, local-only mode. */
class LocalAccessTest {
    @TempDir
    lateinit var dir: File

    private val clock = TestClock()
    private val log = AppLog(clock)

    @Test
    fun `Access lost`() {
        val clients = ClientRegistry(JsonStore(File(dir, "clients.json"), StoredClients.serializer(), TestScope(), log), clock)
        val signer = ImageSigner(JsonStore(File(dir, "secret.json"), StoredSecret.serializer(), TestScope(), log), clock).also { it.load() }
        val pairing = PairingManager(clients, clock, log, { "http://192.168.100.215:8080/" })
        val (token1, laptop) = clients.create("Laptop")
        val (token2, _) = clients.create("Phone")
        val photo = "local-" + "a".repeat(40)
        val url = signer.sign(photo)
        pairing.setShownOnFrame(false)
        val before = pairing.display.value.code
        val invite = pairing.createInvite(laptop.id, "http://192.168.100.215:8080/").url.substringAfter("#pair=")

        PairingAdmin(clients, signer, pairing, log).resetAll()

        assertNull(clients.authenticate(token1))
        assertNull(clients.authenticate(token2))
        assertFalse(signer.verify(photo, url))
        val shown = pairing.display.value.code
        assertNotNull(shown, "The frame shows a new pairing code")
        assertNotEquals(before, shown)
        assertTrue(
            pairing.display.value.url!!
                .startsWith("http://192.168.100.215:8080/#pair="),
            "and a new QR code",
        )
        // The open invitation is invalid.
        val rejected = assertThrows(HttpException::class.java) { pairing.pairWithSecret(invite, "Guest") }
        assertEquals(401, rejected.status)
    }

    @Test
    fun `Local-only on`() {
        val lan =
            NetworkInterface
                .getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address }
        assumeTrue(lan != null, "No LAN interface on the test system")
        val auth =
            AccessControl(
                ClientRegistry(JsonStore(File(dir, "c.json"), StoredClients.serializer(), TestScope(), log), clock),
                ImageSigner(JsonStore(File(dir, "s.json"), StoredSecret.serializer(), TestScope(), log), clock).also { it.load() },
            )
        val router = Router().apply { get("/api/v1/info", Access.PUBLIC) { HttpResponse.noContent() } }
        val server = FrameServer(0, Dispatchers.IO, log, HttpPipeline(router, auth, log)::handle)
        try {
            server.start(localOnly = true)
            val port = server.boundPort!!
            assertEquals(true, connects("127.0.0.1", port), "Reachable from the device itself")
            assertEquals(false, connects(lan!!.hostAddress, port), "Not reachable from the LAN")

            server.start(localOnly = false)
            assertEquals(true, connects(lan.hostAddress, server.boundPort!!), "Turned off on the frame: reachable from the LAN again")
        } finally {
            server.stop()
        }
    }

    private fun connects(
        host: String,
        port: Int,
    ): Boolean =
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), 1000) }
            true
        } catch (e: java.io.IOException) {
            false
        }
}
