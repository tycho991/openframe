package io.github.tycho991.openframe.core.http

import io.github.tycho991.openframe.core.log.AppLog
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Lebenszyklus des HTTP-Servers. Im Modus „nur lokal“ bindet er an
 * `127.0.0.1` und ist aus dem LAN nicht erreichbar, sonst an alle Adressen.
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

    /** Der gebundene Port oder `null`, wenn der Server nicht läuft. */
    val boundPort: Int? get() = server?.takeIf { it.isAlive }?.port

    @Synchronized
    fun start(localOnly: Boolean) {
        stop()
        this.localOnly = localOnly
        val host = if (localOnly) LOOPBACK else ALL
        val s = NanoHttpServer(host, port, dispatcher, handler)
        s.startServer()
        server = s
        log.i(TAG, "Server auf $host:${s.port}" + if (localOnly) " (nur lokal)" else "")
    }

    /** Startet neu, etwa nach einem WLAN-Wechsel oder beim Umschalten von „nur lokal“. */
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
