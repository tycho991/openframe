package io.github.tycho991.openframe.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

/** Spec `display-schedule`: Asking for the network while the display is off, on the Android side. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidNetworkLinkTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Suppress("DEPRECATION")
    private val wifi get() = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val connectivity get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Adds a connected network of [transports] to Robolectric's `ConnectivityManager`. */
    @Suppress("DEPRECATION")
    private fun addConnected(
        id: Int,
        type: Int,
        vararg transports: Int,
    ): Network {
        val network = ShadowNetwork.newInstance(id)
        val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, type, 0, true, NetworkInfo.State.CONNECTED)
        shadowOf(connectivity).addNetwork(network, info)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        transports.forEach { shadowOf(capabilities).addTransportType(it) }
        shadowOf(connectivity).setNetworkCapabilities(network, capabilities)
        return network
    }

    private fun registered(): ConnectivityManager.NetworkCallback = shadowOf(connectivity).networkCallbacks.single()

    @Suppress("DEPRECATION")
    @Test
    fun `A reconnect request reaches the WifiManager as reconnect and scan`() {
        // The network the device was last on, which reconnect connects to again.
        val id = wifi.addNetwork(WifiConfiguration().apply { SSID = "\"home\"" })
        wifi.enableNetwork(id, true)
        shadowOf(wifi).setScanResults(listOf(ScanResult()))
        AndroidNetworkLink(context).requestReconnect()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(wifi.connectionInfo.ssid, wifi.connectionInfo.ssid.contains("home"))
        val broadcasts = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.map { it.action }
        assertTrue(broadcasts.toString(), WifiManager.SCAN_RESULTS_AVAILABLE_ACTION in broadcasts)
    }

    /** Spec `display-schedule` › Asking for the network while the display is off › Mobile data stays on. */
    @Test
    fun `The LAN request asks for Wi-Fi or Ethernet, not mobile data`() {
        val request = AndroidNetworkLink.lanRequest()
        assertTrue(request.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        assertTrue(request.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        assertFalse(request.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        assertTrue(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
        assertFalse("a Wi-Fi without an uplink counts", request.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
    }

    /** Spec `display-schedule` › Asking for the network while the display is off › Mobile data only. */
    @Suppress("DEPRECATION")
    @Config(sdk = [23, 28])
    @Test
    fun `Mobile data alone is no LAN`() {
        // Robolectric's default: Wi-Fi disconnected, mobile data connected and active.
        assertTrue("counted as connected before android-platform-fixes", connectivity.activeNetworkInfo?.isConnected == true)
        assertFalse(AndroidNetworkLink.lanSnapshot(connectivity))
        assertFalse(AndroidNetworkLink(context).connected)
    }

    @Config(sdk = [23, 28])
    @Test
    fun `A connected Wi-Fi is the LAN`() {
        addConnected(100, ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI)
        assertTrue(AndroidNetworkLink.lanSnapshot(connectivity))
        assertTrue(AndroidNetworkLink(context).connected)
    }

    @Config(sdk = [23, 28])
    @Test
    fun `A connected Ethernet is the LAN`() {
        addConnected(100, ConnectivityManager.TYPE_ETHERNET, NetworkCapabilities.TRANSPORT_ETHERNET)
        assertTrue(AndroidNetworkLink.lanSnapshot(connectivity))
    }

    @Config(sdk = [23, 28])
    @Test
    fun `A VPN alone is no LAN`() {
        shadowOf(connectivity).clearAllNetworks()
        // A VPN carries the transport of the network under it as well.
        addConnected(100, ConnectivityManager.TYPE_VPN, NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI)
        assertFalse(AndroidNetworkLink.lanSnapshot(connectivity))
    }

    @Test
    fun `Registering leaves exactly one callback, unregistering none`() {
        shadowOf(connectivity).setStrictUnregistration(true)
        val link = AndroidNetworkLink(context)
        link.register(connectivity) {}
        val first = registered()
        link.register(connectivity) {}
        val second = registered()
        assertNotSame("a new object per registration", first, second)
        link.unregister(connectivity)
        assertTrue(shadowOf(connectivity).networkCallbacks.isEmpty())
        // From API 26 Android throws for a callback unregistered before; the link does not try again.
        link.unregister(connectivity)
        link.unregister(connectivity)
    }

    @Test
    fun `A callback before the snapshot wins over it`() {
        val link = AndroidNetworkLink(context)
        assertFalse(link.connected)
        val wifiNetwork = ShadowNetwork.newInstance(100)
        link.register(connectivity, {}) {
            registered().onAvailable(wifiNetwork)
            false
        }
        assertTrue(link.connected)
    }

    @Test
    fun `A lost and a regained network reach the listener`() {
        val link = AndroidNetworkLink(context)
        link.register(connectivity) {}
        val callback = registered()
        val wifiNetwork = ShadowNetwork.newInstance(100)
        val ethernet = ShadowNetwork.newInstance(101)
        callback.onAvailable(wifiNetwork)
        var changes = 0
        link.setListener { changes++ }
        assertTrue(link.connected)
        callback.onLost(wifiNetwork)
        assertFalse(link.connected)
        assertEquals(1, changes)
        callback.onAvailable(wifiNetwork)
        callback.onAvailable(ethernet)
        assertTrue(link.connected)
        assertEquals(2, changes)
        callback.onLost(wifiNetwork)
        assertTrue("still on Ethernet", link.connected)
        assertEquals(2, changes)
    }

    @Test
    fun `A Wi-Fi available and lost is logged and reported once`() {
        addConnected(100, ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI)
        val link = AndroidNetworkLink(context)
        val lines = mutableListOf<String>()
        var changes = 0
        link.setListener { changes++ }
        link.register(connectivity) { lines += it }
        assertTrue(link.connected)
        val wifiNetwork = ShadowNetwork.newInstance(100)
        registered().onAvailable(wifiNetwork)
        // Android 5 repeats onAvailable whenever the network's score changes.
        registered().onAvailable(wifiNetwork)
        registered().onLost(wifiNetwork)
        assertFalse(link.connected)
        assertEquals(1, changes)
        assertEquals(listOf("LAN network available", "LAN network lost"), lines)
    }

    @Test
    fun `A new registration starts without the networks of the last one`() {
        val link = AndroidNetworkLink(context)
        link.register(connectivity) {}
        val old = registered()
        old.onAvailable(ShadowNetwork.newInstance(100))
        assertTrue(link.connected)
        link.unregister(connectivity)
        // Lost while no callback was registered; the snapshot says no LAN.
        link.register(connectivity) {}
        assertFalse(link.connected)
        val other = ShadowNetwork.newInstance(101)
        registered().onAvailable(other)
        registered().onLost(other)
        assertFalse("the network of the last registration is gone", link.connected)
        old.onAvailable(ShadowNetwork.newInstance(102))
        assertFalse("a late callback of the last registration changes nothing", link.connected)
    }

    /** Spec `kiosk-host` › Network is optional › Same network reported again. */
    @Test
    fun `A network reported available again without a loss is not new`() {
        val known = KnownNetworks()
        val wifiNetwork = ShadowNetwork.newInstance(100)
        assertTrue(known.add(wifiNetwork))
        // Android 5 repeats onAvailable whenever the network's score changes; the server must not rebind for that.
        assertFalse(known.add(wifiNetwork))
        assertFalse(known.add(wifiNetwork))
        assertTrue(known.remove(wifiNetwork))
        assertFalse("lost twice", known.remove(wifiNetwork))
        assertTrue("back after a real loss", known.add(wifiNetwork))
    }
}
