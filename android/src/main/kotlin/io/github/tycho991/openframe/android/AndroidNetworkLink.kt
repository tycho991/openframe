package io.github.tycho991.openframe.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.util.Log
import io.github.tycho991.openframe.core.platform.NetworkLink

/**
 * The network as the `NetworkCallback` of the [FrameService] reports it, any network with internet, Wi-Fi or Ethernet,
 * and asking Android to reconnect to the Wi-Fi (spec `display-schedule`, Asking for the network while the display is
 * off). The decisions are made in :core.
 */
class AndroidNetworkLink(
    context: Context,
) : NetworkLink {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val networks = HashSet<Network>()

    @Volatile
    private var listener: NetworkLink.Listener? = null

    /** Until the callback reports its networks, what the system says now, so that a start is not taken for a loss. */
    @Suppress("DEPRECATION")
    @Volatile
    override var connected: Boolean =
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetworkInfo?.isConnected == true
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
     * From the `NetworkCallback` of the [FrameService]. `true` only for a network that was not available yet: Android 5
     * reports a network available again whenever its score changes, without losing it in between.
     */
    fun available(network: Network): Boolean = update { networks.add(network) }

    fun lost(network: Network): Boolean = update { networks.remove(network) }

    /** Applies [change] and returns its result, whether the set of networks changed. */
    private fun update(change: () -> Boolean): Boolean {
        val (changed, now) =
            synchronized(networks) {
                change() to networks.isNotEmpty()
            }
        if (now != connected) {
            connected = now
            listener?.connectionChanged()
        }
        return changed
    }

    private companion object {
        const val TAG = "openframe/network"
    }
}
