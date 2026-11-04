package io.github.tycho991.openframe.core.http

import io.github.tycho991.openframe.core.log.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the server is bound, as the frame shows it (spec `kiosk-host`, "Server port unavailable"). */
sealed interface ServerState {
    data object Stopped : ServerState

    data class Running(
        val port: Int,
    ) : ServerState

    /** The port could not be bound, for example because another app holds it. */
    data class Failed(
        val message: String,
    ) : ServerState
}

/**
 * Lifecycle of the HTTP server. In "local only" mode it binds to
 * `127.0.0.1` and is not reachable from the LAN, otherwise to all addresses.
 *
 * A bind failure does not throw: it is logged and published as [ServerState.Failed]; `FrameCore` retries.
 */
class FrameServer(
    private val port: Int,
    private val dispatcher: CoroutineDispatcher,
    private val log: AppLog,
    private val handler: suspend (HttpRequest) -> HttpResponse,
) {
    private var server: NanoHttpServer? = null
    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)

    val state: StateFlow<ServerState> = _state.asStateFlow()

    @Volatile
    var localOnly: Boolean = false
        private set

    /** The bound port or `null` if the server is not running. */
    val boundPort: Int? get() = server?.takeIf { it.isAlive }?.port

    /** Binds anew, also when it is running: after a Wi-Fi change or when toggling "local only". */
    @Synchronized
    fun start(localOnly: Boolean) {
        server?.stop()
        server = null
        this.localOnly = localOnly
        val host = if (localOnly) LOOPBACK else ALL
        val s = NanoHttpServer(host, port, dispatcher, handler)
        try {
            s.startServer()
        } catch (e: Exception) {
            // The same message on every retry collapses in the log.
            log.e(TAG, "Server could not bind $host:$port: $e")
            _state.value = ServerState.Failed(e.toString())
            return
        }
        server = s
        _state.value = ServerState.Running(s.port)
        log.i(TAG, "Server on $host:${s.port}" + if (localOnly) " (local only)" else "")
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        _state.value = ServerState.Stopped
    }

    private companion object {
        const val TAG = "server"
        const val LOOPBACK = "127.0.0.1"
        const val ALL = "0.0.0.0"
    }
}
