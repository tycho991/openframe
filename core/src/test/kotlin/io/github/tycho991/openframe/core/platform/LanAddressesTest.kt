package io.github.tycho991.openframe.core.platform

import io.github.tycho991.openframe.core.platform.ConnectionKind.CELLULAR
import io.github.tycho991.openframe.core.platform.ConnectionKind.ETHERNET
import io.github.tycho991.openframe.core.platform.ConnectionKind.OTHER
import io.github.tycho991.openframe.core.platform.ConnectionKind.VPN
import io.github.tycho991.openframe.core.platform.ConnectionKind.WIFI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LanAddressesTest {
    private fun pick(vararg candidates: Pair<String, ConnectionKind>) =
        lanAddresses(candidates.map { (address, kind) -> AddressCandidate(address, kind) })

    @Test
    fun `Galaxy Tab S7 with Wi-Fi and a SIM shows the Wi-Fi address although mobile data is listed first`() {
        assertEquals(
            listOf("192.168.100.7"),
            pick("10.100.200.100" to CELLULAR, "192.168.100.7" to WIFI),
        )
    }

    @Test
    fun `Wi-Fi only, as on the Frameo and the Lenovo`() {
        assertEquals(listOf("192.168.100.215"), pick("192.168.100.215" to WIFI))
    }

    @Test
    fun `Mobile data only gives no address`() {
        assertEquals(emptyList<String>(), pick("10.100.200.100" to CELLULAR))
    }

    @Test
    fun `No candidates give no address`() {
        assertEquals(emptyList<String>(), pick())
    }

    @Test
    fun `Wi-Fi comes before Ethernet and each keeps its order`() {
        assertEquals(
            listOf("192.168.100.7", "192.168.100.8", "192.168.100.20"),
            pick("192.168.100.20" to ETHERNET, "192.168.100.7" to WIFI, "192.168.100.8" to WIFI),
        )
    }

    @Test
    fun `A hotspot interface is shown after Wi-Fi and Ethernet`() {
        assertEquals(
            listOf("192.168.100.7", "192.168.43.1"),
            pick("192.168.43.1" to OTHER, "192.168.100.7" to WIFI),
        )
        assertEquals(listOf("192.168.43.1"), pick("10.100.200.100" to CELLULAR, "192.168.43.1" to OTHER))
    }

    @Test
    fun `A VPN address is dropped`() {
        assertEquals(listOf("192.168.100.7"), pick("10.8.0.2" to VPN, "192.168.100.7" to WIFI))
    }

    @Test
    fun `The same address on two interfaces is listed once`() {
        assertEquals(listOf("192.168.100.7"), pick("192.168.100.7" to WIFI, "192.168.100.7" to OTHER))
    }
}
