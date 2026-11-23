package io.github.tycho991.openframe.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.util.Log
import io.github.tycho991.openframe.core.platform.ConnectionKind
import io.github.tycho991.openframe.core.platform.NetworkLink

/**
 * The frame's LAN, a Wi-Fi or Ethernet connection, as a `NetworkCallback` of its own reports it, and asking Android to
 * reconnect to the Wi-Fi (spec `display-schedule`, Asking for the network while the display is off). Mobile data, a
 * VPN or any other kind of connection does not count. The [FrameService] registers the callback with [register]; the
 * decisions are made in :core.
 */
class AndroidNetworkLink(
    context: Context,
) : NetworkLink {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val lock = Any()

    /** The LAN networks the current callback reported; guarded by [lock]. */
    private val networks = HashSet<Network>()

    /** The callback of the current registration, `null` while none is registered; guarded by [lock]. */
    private var callback: LanCallback? = null

    @Volatile
    private var listener: NetworkLink.Listener? = null

    /**
     * Whether a Wi-Fi or Ethernet network is connected. Until the first callback of a registration, the snapshot of
     * what the system says now, so that a start is not taken for a loss: the core can evaluate before the
     * [FrameService] registers anything.
     */
    @Volatile
    override var connected: Boolean =
        lanSnapshot(context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
        private set

    /** Android ignores [WifiManager.reconnect] for apps from Android 10 on and throttles scans; the dark wake is the fallback. */
    @Suppress("DEPRECATION")
    override fun requestReconnect() {
        try {
            wifi.reconnect()
            wifi.startScan()
        } catch (e: SecurityException) {
            Log.w(TAG, "Reconnect refused", e)
        }
    }

    override fun setListener(listener: NetworkLink.Listener) {
        this.listener = listener
    }

    /**
     * Registers a fresh callback for the LAN, after unregistering one registered before, and starts from an empty set
     * of networks: the link lives as long as the process, the [FrameService] does not. [log] gets "LAN network
     * available" and "LAN network lost" whenever the set changes. The snapshot read after the registration applies only
     * while no callback of this registration has arrived, since a callback knows better.
     */
    fun register(
        connectivity: ConnectivityManager,
        log: (String) -> Unit,
    ) = register(connectivity, log) { lanSnapshot(connectivity) }

    /** [register] with the [snapshot] a test can interleave with a callback. */
    internal fun register(
        connectivity: ConnectivityManager,
        log: (String) -> Unit,
        snapshot: () -> Boolean,
    ) {
        val fresh = LanCallback(log)
        val previous =
            synchronized(lock) {
                val old = callback
                callback = fresh
                networks.clear()
                old
            }
        previous?.let { unregisterQuietly(connectivity, it) }
        connectivity.registerNetworkCallback(lanRequest(), fresh)
        val now = snapshot()
        val changed = synchronized(lock) { callback === fresh && !fresh.heard && setConnectedLocked(now) }
        if (changed) listener?.connectionChanged()
    }

    /** Unregisters the callback of [register]; nothing happens when none is registered. */
    fun unregister(connectivity: ConnectivityManager) {
        val current =
            synchronized(lock) {
                val old = callback ?: return
                callback = null
                old
            }
        unregisterQuietly(connectivity, current)
    }

    /** From API 26 unregistering a callback the system does not know throws; nothing is left to undo then. */
    private fun unregisterQuietly(
        connectivity: ConnectivityManager,
        callback: ConnectivityManager.NetworkCallback,
    ) {
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Network callback was not registered", e)
        }
    }

    /** Under [lock], so that the snapshot and a callback cannot overwrite each other; `true` if [connected] changed. */
    private fun setConnectedLocked(now: Boolean): Boolean {
        if (now == connected) return false
        connected = now
        return true
    }

    /** A new object per registration: newer releases reject registering the same callback object twice. */
    private inner class LanCallback(
        private val log: (String) -> Unit,
    ) : ConnectivityManager.NetworkCallback() {
        /** A callback of this registration arrived; guarded by [lock]. */
        var heard = false

        override fun onAvailable(network: Network) = update(network, add = true)

        override fun onLost(network: Network) = update(network, add = false)

        private fun update(
            network: Network,
            add: Boolean,
        ) {
            val (changed, connectionChanged) =
                synchronized(lock) {
                    // A late callback of an earlier registration.
                    if (callback !== this) return
                    heard = true
                    val changed = if (add) networks.add(network) else networks.remove(network)
                    changed to setConnectedLocked(networks.isNotEmpty())
                }
            // A repeated onAvailable for a known network (Android 5 after a score change) logs nothing.
            if (changed) log(if (add) "LAN network available" else "LAN network lost")
            if (connectionChanged) listener?.connectionChanged()
        }
    }

    internal companion object {
        private const val TAG = "openframe/network"

        /**
         * Wi-Fi or Ethernet, the transports being alternatives; `NOT_VPN` explicit although it is a default, and no
         * `INTERNET`, since a Wi-Fi without an uplink is still the LAN the admin interface needs.
         */
        fun lanRequest(): NetworkRequest =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()

        /**
         * Whether a Wi-Fi or Ethernet network is connected now. `allNetworks` also lists networks that are still coming
         * up, hence the check of their `NetworkInfo`; `null` capabilities belong to a network going away. A VPN on top
         * of the Wi-Fi does not count, the Wi-Fi under it does.
         */
        @Suppress("DEPRECATION")
        fun lanSnapshot(connectivity: ConnectivityManager): Boolean =
            try {
                connectivity.allNetworks.any { network ->
                    val capabilities = connectivity.getNetworkCapabilities(network) ?: return@any false
                    val kind = connectionKind(capabilities)
                    // As the request: its builder asks for NOT_RESTRICTED and TRUSTED by default.
                    (kind == ConnectionKind.WIFI || kind == ConnectionKind.ETHERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED) &&
                        connectivity.getNetworkInfo(network)?.isConnected == true
                }
            } catch (e: Exception) {
                try {
                    val info = connectivity.activeNetworkInfo
                    info != null &&
                        info.isConnected &&
                        (info.type == ConnectivityManager.TYPE_WIFI || info.type == ConnectivityManager.TYPE_ETHERNET)
                } catch (e: Exception) {
                    false
                }
            }
    }
}

/**
 * The networks the [FrameService]'s unfiltered callback reported, to tell a new network from one reported again: Android
 * 5 reports a network available again whenever its score changes, without losing it in between, and a rebind for that
 * would drop every open connection. One instance per process in [FrameApp], since a service restarted by
 * `START_STICKY` gets `onAvailable` for the network it is on, which is not new.
 */
internal class KnownNetworks {
    private val networks = HashSet<Network>()

    /** `true` only for a network not known yet. */
    @Synchronized
    fun add(network: Network): Boolean = networks.add(network)

    /** `true` only for a network that was known. */
    @Synchronized
    fun remove(network: Network): Boolean = networks.remove(network)
}
