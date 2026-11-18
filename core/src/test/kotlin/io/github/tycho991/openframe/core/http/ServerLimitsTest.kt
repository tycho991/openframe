package io.github.tycho991.openframe.core.http

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.log.LogLevel
import io.github.tycho991.openframe.core.testing.Contract
import io.github.tycho991.openframe.core.testing.PairingHarness
import io.github.tycho991.openframe.core.testing.RawRes
import io.github.tycho991.openframe.core.testing.TestClock
import io.github.tycho991.openframe.core.testing.TestHttp
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Spec `admin-access`: Limits for connections and request times, Size of request bodies without token. The flood
 * comes from `127.0.0.2` and the probe from `127.0.0.1`, both on loopback; deadlines are shortened.
 */
class ServerLimitsTest {
    @TempDir
    lateinit var dir: File

    private val limits =
        ServerLimits(idleTimeoutMs = 3_000, headerTimeoutMs = 1_000, jsonBodyTimeoutMs = 1_000, drainTimeoutMs = 1_000)
    private val log = AppLog(TestClock())
    private val router =
        Router().apply {
            get("/api/v1/info", Access.PUBLIC) { HttpResponse.Json(200, """{"ok":true}""") }
            // Answers without reading the body.
            post("/api/v1/ignore", Access.PUBLIC) { HttpResponse.noContent() }
        }
    private val closeables = mutableListOf<AutoCloseable>()
    private val sockets = ConcurrentLinkedQueue<Socket>()

    @AfterEach
    fun tearDown() {
        sockets.forEach { runCatching { it.close() } }
        closeables.forEach { runCatching { it.close() } }
    }

    private fun server(limits: ServerLimits = this.limits) =
        TestHttp(HttpPipeline(router, NO_AUTH, log)::handle, limits = limits).also { closeables += it }

    private fun connect(
        port: Int,
        from: String,
    ): Socket {
        val socket = Socket()
        socket.bind(InetSocketAddress(InetAddress.getByName(from), 0))
        socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
        socket.soTimeout = 10_000
        sockets += socket
        return socket
    }

    private fun handlerThreads(port: Int) = Thread.getAllStackTraces().keys.count { it.name.startsWith("http-$port-") && it.isAlive }

    /** Time of a block in seconds. */
    private inline fun seconds(block: () -> Unit): Double {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1e9
    }

    @Test
    fun `Flood from one device`() {
        val http = server()
        val port = http.port()
        val stop = AtomicBoolean(false)
        val flood = ConcurrentLinkedQueue<Socket>()
        val flooder =
            thread {
                repeat(200) {
                    runCatching {
                        val s = connect(port, "127.0.0.2")
                        s.getOutputStream().write("G".toByteArray())
                        flood += s
                    }
                }
                // Slowloris: one more byte now and then on every connection that is still open.
                while (!stop.get()) {
                    flood.forEach { s -> runCatching { s.getOutputStream().write("E".toByteArray()) } }
                    Thread.sleep(200)
                }
            }
        try {
            Thread.sleep(500)
            repeat(5) {
                val elapsed = seconds { assertEquals(200, http.get("/api/v1/info").statusCode()) }
                assertTrue(elapsed < 1.0, "answer after $elapsed s")
                assertTrue(handlerThreads(port) < 48 + 4, "${handlerThreads(port)} threads")
                Thread.sleep(200)
            }
        } finally {
            stop.set(true)
            flooder.join(10_000)
        }
    }

    @Test
    fun `Many idle connections`() {
        val http = server()
        val port = http.port()
        val idle = (1..16).map { connect(port, "127.0.0.2").also { s -> assertEquals(200, exchange(s).status) } }
        // One more request of the same address: an idle connection makes room for it.
        val extra = connect(port, "127.0.0.2")
        assertEquals(200, exchange(extra).status)
        assertEquals(1, idle.count { s -> runCatching { exchange(s) }.isFailure }, "Exactly the oldest idle one was closed")
    }

    @Test
    fun `A connection that cannot get a thread ends, the listener goes on`() {
        val starts = AtomicInteger()
        val server =
            NanoHttpServer("127.0.0.1", 0, Dispatchers.IO, limits, { runnable, name ->
                if (starts.incrementAndGet() == 1) throw OutOfMemoryError("unable to create native thread")
                Thread(runnable, name).apply { isDaemon = true }.start()
            }, HttpPipeline(router, NO_AUTH, log)::handle).also { it.startServer() }
        closeables += AutoCloseable { server.stop() }
        val first = connect(server.port, "127.0.0.1")
        first.getOutputStream().write(REQUEST.toByteArray())
        assertEquals(-1, runCatching { first.getInputStream().read() }.getOrDefault(-1), "The first connection is closed")
        assertEquals(200, exchange(connect(server.port, "127.0.0.1")).status)
    }

    @Test
    fun `A failing accept backs off instead of spinning`() {
        BackoffServerSocket(100).use { socket ->
            socket.bind(InetSocketAddress("127.0.0.1", 0))
            socket.soTimeout = 1
            val elapsed = seconds { repeat(5) { runCatching { socket.accept() } } }
            assertTrue(elapsed >= 0.5, "five failures in $elapsed s")
        }
        val closed = BackoffServerSocket(10_000).also { it.close() }
        val elapsed = seconds { assertTrue(runCatching { closed.accept() }.isFailure) }
        assertTrue(elapsed < 1.0, "no pause once closed")
    }

    @Test
    fun `Headers sent byte by byte end at the header deadline`() {
        val http = server()
        val socket = connect(http.port(), "127.0.0.1")
        val stop = AtomicBoolean(false)
        val writer =
            thread {
                runCatching {
                    socket.getOutputStream().write("GET /api/v1/info HTTP/1.1\r\n".toByteArray())
                    while (!stop.get()) {
                        socket.getOutputStream().write("X".toByteArray())
                        Thread.sleep(100)
                    }
                }
            }
        val elapsed = seconds { assertEquals(-1, runCatching { socket.getInputStream().read() }.getOrDefault(-1)) }
        stop.set(true)
        writer.join(2_000)
        assertTrue(elapsed in 0.8..2.5, "closed after $elapsed s")
    }

    @Test
    fun `A trickling drain ends at its deadline`() {
        val http = server()
        val socket = connect(http.port(), "127.0.0.1")
        val stop = AtomicBoolean(false)
        socket.getOutputStream().write(
            "POST /api/v1/ignore HTTP/1.1\r\nHost: x\r\nContent-Type: application/octet-stream\r\nContent-Length: 1000000\r\n\r\n"
                .toByteArray(),
        )
        val writer =
            thread {
                runCatching {
                    while (!stop.get()) {
                        socket.getOutputStream().write("x".toByteArray())
                        Thread.sleep(50)
                    }
                }
            }
        try {
            val elapsed = seconds { assertEquals(204, readResponse(socket.getInputStream()).status) }
            assertTrue(elapsed < limits.drainTimeoutMs / 1000.0 + 1.5, "answer after $elapsed s")
        } finally {
            stop.set(true)
            writer.join(2_000)
        }
    }

    @Test
    fun `A body that is announced but not sent`() {
        val http = server()
        val socket = connect(http.port(), "127.0.0.1")
        socket.getOutputStream().write(
            "POST /api/v1/ignore HTTP/1.1\r\nHost: x\r\nContent-Type: application/octet-stream\r\nContent-Length: 1000\r\n\r\n"
                .toByteArray(),
        )
        val elapsed = seconds { assertEquals(204, readResponse(socket.getInputStream()).status) }
        assertTrue(elapsed < limits.drainTimeoutMs / 1000.0 + 1.5, "answer after $elapsed s")
    }

    @Test
    fun `Slow body`() {
        PairingHarness(dir, limits = limits).use { h ->
            val socket = connect(h.http.port(), "127.0.0.1")
            socket.getOutputStream().write(
                "POST /api/v1/pairing HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nContent-Length: 20\r\n\r\n{\"code\"".toByteArray(),
            )
            var answer: RawRes? = null
            val elapsed = seconds { answer = readResponse(socket.getInputStream()) }
            val res = answer!!
            Contract.check("POST", "/api/v1/pairing", res.status, res.headers["content-type"], res.body)
            assertEquals(408, res.status, res.body)
            assertTrue("\"request_timeout\"" in res.body, res.body)
            assertEquals("DENY", res.headers["x-frame-options"])
            assertEquals("close", res.headers["connection"]?.lowercase())
            assertTrue(elapsed in 0.8..2.5, "answer after $elapsed s")
            assertFalse(h.log.recent(100).any { it.level == LogLevel.ERROR }, "${h.log.recent(100)}")
        }
    }

    @Test
    fun `Oversized pairing body`() {
        PairingHarness(dir).use { h ->
            val body = """{"code":"123456","deviceName":"${"x".repeat(10 * 1024)}"}"""
            val res = h.http.request("POST", "/api/v1/pairing", body)
            assertEquals(413, res.statusCode())
            // 4 KiB pass the limit and get their usual answer.
            val small = """{"code":"123456","deviceName":"${"x".repeat(3 * 1024)}"}"""
            assertEquals(401, h.http.request("POST", "/api/v1/pairing", small).statusCode())
        }
    }

    @Test
    fun `Slow bodies and handlers`() {
        // Four handler slots, ten slow pairing bodies.
        PairingHarness(dir, limits = limits.copy(jsonBodyTimeoutMs = 5_000)).use { h ->
            val port = h.http.port()
            val slow =
                (1..10).map {
                    connect(port, "127.0.0.2").also { s ->
                        s.getOutputStream().write(
                            "POST /api/v1/pairing HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nContent-Length: 30\r\n\r\n{"
                                .toByteArray(),
                        )
                    }
                }
            Thread.sleep(300)
            repeat(3) {
                val info = seconds { assertEquals(204, h.http.request("POST", "/api/v1/pairing/code").statusCode()) }
                assertTrue(info < 1.0, "answer after $info s")
                val web = seconds { assertEquals(404, h.http.get("/").statusCode()) }
                assertTrue(web < 1.0, "answer after $web s")
            }
            slow.forEach { it.close() }
        }
    }

    /** Sends a request on a kept-alive connection and reads its answer. */
    private fun exchange(socket: Socket): RawRes {
        socket.getOutputStream().write(REQUEST.toByteArray())
        return readResponse(socket.getInputStream())
    }

    /** Reads one answer with `Content-Length` from [input], leaving the connection open. */
    private fun readResponse(input: InputStream): RawRes {
        val head = ByteArrayOutputStream()
        while (!head.toString(Charsets.UTF_8.name()).endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) throw IOException("Connection closed")
            head.write(b)
        }
        val parsed = RawRes.parse(head.toByteArray())
        val length = parsed.headers["content-length"]?.toInt() ?: 0
        val body = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(body, off, length - off)
            if (n < 0) throw IOException("Connection closed")
            off += n
        }
        return RawRes(parsed.status, parsed.headers, body.toString(Charsets.UTF_8))
    }

    private companion object {
        const val REQUEST = "GET /api/v1/info HTTP/1.1\r\nHost: x\r\n\r\n"

        val NO_AUTH =
            object : Authenticator {
                override fun clientFor(request: HttpRequest): String? = null

                override fun hasValidImageSignature(
                    request: HttpRequest,
                    photoId: String,
                ) = false
            }
    }
}
