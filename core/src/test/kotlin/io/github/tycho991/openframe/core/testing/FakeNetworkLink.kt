package io.github.tycho991.openframe.core.testing

import io.github.tycho991.openframe.core.platform.NetworkLink

/** Network that records the frame's reconnect requests; the test drops and restores it, as an access point would. */
class FakeNetworkLink : NetworkLink {
    @Volatile
    override var connected: Boolean = true
        private set

    /** How often the frame asked for a reconnect. */
    @Volatile
    var requests = 0
        private set

    private var listener: NetworkLink.Listener? = null

    override fun requestReconnect() {
        requests++
    }

    override fun setListener(listener: NetworkLink.Listener) {
        this.listener = listener
    }

    /** The access point drops the device. */
    fun drop() = set(false)

    /** The device is connected again. */
    fun restore() = set(true)

    private fun set(value: Boolean) {
        if (connected == value) return
        connected = value
        listener?.connectionChanged()
    }
}
