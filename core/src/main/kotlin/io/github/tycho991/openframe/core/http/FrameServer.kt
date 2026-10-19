package io.github.tycho991.openframe.core.http

import io.github.tycho991.openframe.core.log.AppLog
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Lifecycle of the HTTP server. In "local only" mode it binds to
 * `127.0.0.1` and is not reachable from the LAN, otherwise to all addresses.
 */
class FrameServer(
    private val port: Int,
    private val dispatcher: CoroutineDispatcher,
    private val log: AppLog,
    private val handler: suspend (HttpRequest) -> HttpResponse,
) {
    private var server: NanoHttpServer? = null

    @Volatile
    var localOnly: Boolean = false
        private set

    /** The bound port or `null` if the server is not running. */
    val boundPort: Int? get() = server?.takeIf { it.isAlive }?.port

    @Synchronized
    fun start(localOnly: Boolean) {
        stop()
        this.localOnly = localOnly
        val host = if (localOnly) LOOPBACK else ALL
        val s = NanoHttpServer(host, port, dispatcher, handler)
        s.startServer()
        server = s
        log.i(TAG, "Server on $host:${s.port}" + if (localOnly) " (local only)" else "")
    }

    /** Restarts, for example after a Wi-Fi change or when toggling "local only". */
    @Synchronized
    fun restart(localOnly: Boolean = this.localOnly) = start(localOnly)

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }

    private companion object {
        const val TAG = "server"
        const val LOOPBACK = "127.0.0.1"
        const val ALL = "0.0.0.0"
    }
}
