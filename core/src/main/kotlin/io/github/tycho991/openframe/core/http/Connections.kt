package io.github.tycho991.openframe.core.http

import fi.iki.elonen.NanoHTTPD
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Limits of the transport (spec `admin-access`, Limits for connections and request times). Constructor parameters so
 * that tests can shorten them.
 */
data class ServerLimits(
    /** Connections at once, each with a thread of its own. */
    val maxConnections: Int = 48,
    /** Connections at once from one remote address. */
    val maxPerAddress: Int = 16,
    /** How long a connection may wait for the first byte of its next request. */
    val idleTimeoutMs: Int = 30_000,
    /** How long the headers of a request may take from its first byte. */
    val headerTimeoutMs: Int = 10_000,
    /** How long a JSON body may take in total. */
    val jsonBodyTimeoutMs: Int = 10_000,
    /** How long a read of another body, such as an upload, may wait. */
    val bodyReadTimeoutMs: Int = 30_000,
    /** How long the rest of an unread body may take in total. */
    val drainTimeoutMs: Int = 2_000,
    /** The pause after a failed `accept()`, so that the listener does not spin. */
    val acceptBackoffMs: Long = 100,
)

/**
 * The input of a connection with its socket. Before each read it sets the socket's timeout to the smaller of the
 * phase's timeout per read and the time left of the phase, on monotonic time (design decision 2 of harden-lan-server).
 * The connection thread and, for a body, the handler's thread read from it one after the other, never at once.
 */
internal class ConnectionInput(
    private val socket: Socket,
    input: InputStream,
    private val limits: ServerLimits,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : FilterInputStream(input) {
    enum class Phase {
        /** Waiting for the first byte of a request: a new or a kept-alive connection. */
        IDLE,

        /** Reading the headers. */
        HEADER,

        /** A request is being served: the handler runs or the answer goes out. */
        BUSY,

        /** Reading the body. */
        BODY,

        /** Reading away the rest of an unread body. */
        DRAIN,
    }

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    @Volatile
    private var deadline: Long? = null

    @Volatile
    private var perRead: Int = limits.idleTimeoutMs

    /** No request in progress: the connection may be closed to make room for another one. */
    val idle: Boolean get() = phase == Phase.IDLE

    /** The headers are complete and the request is served. */
    fun beginRequest() = enter(Phase.BUSY, null, limits.idleTimeoutMs)

    /** The body is read, within [totalMs] in total if set, else with the timeout per read of other bodies. */
    fun beginBody(totalMs: Int?) = enter(Phase.BODY, totalMs, if (totalMs != null) totalMs else limits.bodyReadTimeoutMs)

    /** The rest of the body is read away within the drain deadline. */
    fun beginDrain() = enter(Phase.DRAIN, limits.drainTimeoutMs, limits.drainTimeoutMs)

    /** The request is served; the next read of NanoHTTPD waits for the next request. */
    fun endRequest() = enter(Phase.BUSY, null, limits.idleTimeoutMs)

    private fun enter(
        next: Phase,
        totalMs: Int?,
        readMs: Int,
    ) {
        phase = next
        deadline = totalMs?.let { nowMillis() + it }
        perRead = readMs
    }

    override fun read(): Int {
        prepare()
        val b = super.read()
        if (b >= 0) started()
        return b
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        prepare()
        val n = super.read(b, off, len)
        if (n > 0) started()
        return n
    }

    // Through read, so that the deadlines also apply here.
    override fun skip(n: Long): Long {
        val buf = ByteArray(minOf(n, 8192L).toInt().coerceAtLeast(1))
        val read = read(buf, 0, buf.size)
        return if (read < 0) 0 else read.toLong()
    }

    private fun prepare() {
        // NanoHTTPD reads again only for the next request once the previous one is answered.
        if (phase == Phase.BUSY) enter(Phase.IDLE, null, limits.idleTimeoutMs)
        val end = deadline
        val timeout =
            if (end == null) {
                perRead
            } else {
                val left = end - nowMillis()
                if (left <= 0) throw SocketTimeoutException("Deadline of the ${phase.name.lowercase()} passed")
                minOf(perRead.toLong(), left).toInt()
            }
        try {
            socket.soTimeout = timeout
        } catch (e: SocketException) {
            // The connection is closed anyway; the read reports it.
        }
    }

    private fun started() {
        if (phase == Phase.IDLE) enter(Phase.HEADER, limits.headerTimeoutMs, minOf(limits.idleTimeoutMs, limits.headerTimeoutMs))
    }
}

/**
 * Runs each connection on a thread of its own, at most [ServerLimits.maxConnections] at once and
 * [ServerLimits.maxPerAddress] from one address (design decision 1 of harden-lan-server). When a limit is reached, it
 * closes an idle connection of the same address, else the oldest idle one, else the new connection. The address comes
 * from the accepted socket, never from a header.
 */
internal class BoundedAsyncRunner(
    private val limits: ServerLimits,
    /** The prefix of the threads' names, read at the first connection when the port is known. */
    private val threadName: () -> String,
    private val startThread: (Runnable, String) -> Unit = { runnable, name ->
        Thread(runnable, name).apply { isDaemon = true }.start()
    },
) : NanoHTTPD.AsyncRunner {
    /** What the runner knows of a connection; [NanoHttpServer] hands it in with the handler. */
    interface Tracked {
        val address: InetAddress?
        val input: ConnectionInput
    }

    /** A connection to close at once, without a slot or a thread (finish-lan-hardening, design decision 1). */
    interface Refused

    private val running = LinkedHashMap<NanoHTTPD.ClientHandler, Tracked?>()
    private var count = 0L

    val size: Int @Synchronized get() = running.size

    override fun exec(handler: NanoHTTPD.ClientHandler) {
        if (handler is Refused) {
            handler.close()
            return
        }
        val tracked = handler as? Tracked
        val (victim, admitted, name) =
            synchronized(this) {
                val address = tracked?.address
                val same = running.entries.filter { it.value?.address == address }
                val idle = { e: Map.Entry<NanoHTTPD.ClientHandler, Tracked?> -> e.value?.input?.idle == true }
                val victim =
                    when {
                        same.size >= limits.maxPerAddress -> same.firstOrNull(idle)
                        running.size >= limits.maxConnections -> same.firstOrNull(idle) ?: running.entries.firstOrNull(idle)
                        else -> null
                    }
                val full = same.size >= limits.maxPerAddress || running.size >= limits.maxConnections
                if (victim != null) running.remove(victim.key)
                val admitted = !full || victim != null
                if (admitted) running[handler] = tracked
                Triple(victim?.key, admitted, "${threadName()}-${++count}")
            }
        victim?.close()
        if (!admitted) {
            handler.close()
            return
        }
        try {
            startThread(handler, name)
        } catch (t: Throwable) {
            // Out of memory or threads: this connection ends, the listener goes on.
            closed(handler)
            handler.close()
        }
    }

    override fun closed(handler: NanoHTTPD.ClientHandler) {
        synchronized(this) { running.remove(handler) }
    }

    override fun closeAll() {
        val all = synchronized(this) { running.keys.toList().also { running.clear() } }
        all.forEach { it.close() }
    }
}

/** A server socket whose failed `accept()` pauses before the next one, unless it is closed (design decision 1). */
internal class BackoffServerSocket(
    private val backoffMs: Long,
) : ServerSocket() {
    override fun accept(): Socket {
        try {
            return super.accept()
        } catch (e: IOException) {
            if (!isClosed) {
                try {
                    Thread.sleep(backoffMs)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            throw e
        }
    }
}
