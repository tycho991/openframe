package io.github.tycho991.openframe.android

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

/** Spec `display-schedule`: Asking for the network while the display is off, on the Android side. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidNetworkLinkTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Suppress("DEPRECATION")
    private val wifi get() = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

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

    @Test
    fun `A lost and a regained network reach the listener`() {
        val link = AndroidNetworkLink(context)
        val wifiNetwork = ShadowNetwork.newInstance(100)
        val ethernet = ShadowNetwork.newInstance(101)
        link.available(wifiNetwork)
        var changes = 0
        link.setListener { changes++ }
        assertTrue(link.connected)
        link.lost(wifiNetwork)
        assertFalse(link.connected)
        assertEquals(1, changes)
        link.available(wifiNetwork)
        link.available(ethernet)
        assertTrue(link.connected)
        assertEquals(2, changes)
        link.lost(wifiNetwork)
        assertTrue("still on Ethernet", link.connected)
        assertEquals(2, changes)
    }

    @Test
    fun `A network reported available again without a loss is not new`() {
        val link = AndroidNetworkLink(context)
        val wifiNetwork = ShadowNetwork.newInstance(100)
        assertTrue(link.available(wifiNetwork))
        // Android 5 repeats onAvailable whenever the network's score changes; the server must not rebind for that.
        assertFalse(link.available(wifiNetwork))
        assertFalse(link.available(wifiNetwork))
        assertTrue(link.lost(wifiNetwork))
        assertFalse("lost twice", link.lost(wifiNetwork))
        assertTrue("back after a real loss", link.available(wifiNetwork))
        assertTrue(link.connected)
    }
}
