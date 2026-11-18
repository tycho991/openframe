package io.github.tycho991.openframe.core.http

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.LocalAddresses
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Socket
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** Spec `admin-access`, "Connections only on local addresses"; finish-lan-hardening, design decisions 1 to 3. */
class LocalAddressTest {
    private val clock = TestClock()
    private val log = AppLog(clock)
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterEach
    fun tearDown() = closeables.reversed().forEach { it.close() }

    private fun ip(literal: String): InetAddress = InetAddress.getByName(literal)

    private fun server(
        host: String,
        acceptsLocal: (InetAddress) -> Boolean,
        starts: AtomicInteger = AtomicInteger(),
    ): NanoHttpServer =
        NanoHttpServer(
            host,
            0,
            Dispatchers.IO,
            ServerLimits(),
            { runnable, name ->
                starts.incrementAndGet()
                Thread(runnable, name).apply { isDaemon = true }.start()
            },
            log,
            acceptsLocal,
            { HttpResponse.Json(200, "{}") },
        ).also {
            it.startServer()
            closeables += AutoCloseable { it.stop() }
        }

    /** The status line of the answer to a GET, or `null` if the frame closed the connection without one. */
    private fun answer(
        host: String,
        port: Int,
    ): String? =
        Socket(host, port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().write("GET /x HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray())
            try {
                s
                    .getInputStream()
                    .readBytes()
                    .toString(Charsets.UTF_8)
                    .lineSequence()
                    .firstOrNull()
                    ?.trim()
                    ?.ifEmpty { null }
            } catch (e: IOException) {
                null
            }
        }

    @Test
    fun `Loopback is answered whatever the device's addresses are`() {
        val filter = LocalAddressFilter(clock, { LocalAddresses(listOf(ip("192.0.2.1")), emptyList()) })
        val server = server("127.0.0.2", filter::accepts)
        assertEquals("HTTP/1.1 200 OK", answer("127.0.0.2", server.port))
    }

    @Test
    fun `A connection on another address is closed without an answer and without a thread`() {
        val starts = AtomicInteger()
        val server = server("127.0.0.1", { false }, starts)
        assertEquals(null, answer("127.0.0.1", server.port))
        assertEquals(null, answer("127.0.0.1", server.port))
        assertEquals(0, starts.get())
        assertEquals(0, server.connections)
        val refusals = log.recent(100).filter { "refused: not a local address" in it.message }
        assertEquals(1, refusals.size, "logged at most once a minute")
        // The listener goes on: an accepting server on the same machine still answers.
        val open = server("127.0.0.1", { true })
        assertEquals("HTTP/1.1 200 OK", answer("127.0.0.1", open.port))
    }

    @Test
    fun `An address that appears without a network change is accepted on its first connection`() {
        var accepted = listOf(ip("192.168.100.7"))
        val filter = LocalAddressFilter(clock, { LocalAddresses(accepted, emptyList()) })
        assertTrue(filter.accepts(ip("192.168.100.7")))
        clock.advance(Duration.ofSeconds(2))
        accepted = accepted + ip("100.64.0.7") // a VPN came up; Android announces it through no callback here
        assertTrue(filter.accepts(ip("100.64.0.7")))
    }

    @Test
    fun `An unknown address reads again at most once a second, a refused one never`() {
        val reads = AtomicInteger()
        val filter =
            LocalAddressFilter(clock, {
                reads.incrementAndGet()
                LocalAddresses(listOf(ip("192.168.100.7")), listOf(ip("10.100.200.100")))
            })
        assertTrue(filter.accepts(ip("192.168.100.7")))
        assertEquals(1, reads.get())
        repeat(5) { assertFalse(filter.accepts(ip("10.100.200.100"))) }
        assertEquals(1, reads.get())
        clock.advance(Duration.ofMillis(1_500))
        assertFalse(filter.accepts(ip("203.0.113.9")))
        assertEquals(2, reads.get())
        assertFalse(filter.accepts(ip("203.0.113.9")))
        clock.advance(Duration.ofMillis(500))
        assertFalse(filter.accepts(ip("203.0.113.9")))
        assertEquals(2, reads.get())
        clock.advance(Duration.ofMillis(600))
        assertFalse(filter.accepts(ip("203.0.113.9")))
        assertEquals(3, reads.get())
        filter.clear()
        assertTrue(filter.accepts(ip("192.168.100.7")))
        assertEquals(4, reads.get())
    }

    @Test
    fun `IPv4-mapped addresses and scopes are compared by their bytes`() {
        val filter =
            LocalAddressFilter(clock, {
                LocalAddresses(listOf(ip("192.168.100.7"), Inet6Address.getByAddress(null, ip("fe80::7").address, 3)), emptyList())
            })
        val mappedLoopback = Inet6Address.getByAddress(null, mapped(127, 0, 0, 1), -1)
        assertTrue(mappedLoopback is Inet6Address)
        assertTrue(filter.accepts(mappedLoopback))
        assertTrue(filter.accepts(Inet6Address.getByAddress(null, mapped(192, 168, 100, 7), -1)))
        assertTrue(filter.accepts(ip("::1")))
        assertTrue(filter.accepts(Inet6Address.getByAddress(null, ip("fe80::7").address, -1)))
        assertFalse(filter.accepts(Inet6Address.getByAddress(null, mapped(192, 168, 100, 8), -1)))
    }

    private fun mapped(vararg ipv4: Int): ByteArray = ByteArray(10) + byteArrayOf(-1, -1) + ipv4.map { it.toByte() }.toByteArray()
}
