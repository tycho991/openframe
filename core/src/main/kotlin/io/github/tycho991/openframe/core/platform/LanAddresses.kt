package io.github.tycho991.openframe.core.platform

/** What kind of connection an interface belongs to, as the system reports it. */
enum class ConnectionKind {
    WIFI,
    ETHERNET,

    /** Not reported as a network of the device, e.g. a Wi-Fi hotspot or USB tethering where the frame is the gateway. */
    OTHER,
    CELLULAR,
    VPN,
}

/** An IPv4 address of this device together with the kind of connection it is on. */
data class AddressCandidate(
    val address: String,
    val kind: ConnectionKind,
)

/**
 * The addresses that a browser on the local network can reach, best first: Wi-Fi, then Ethernet (each in the
 * order given), then the rest that is not a mobile data or VPN connection. Mobile data and VPN addresses are
 * dropped, no other device on the LAN can open them. Duplicates are removed.
 */
fun lanAddresses(candidates: List<AddressCandidate>): List<String> {
    val usable = candidates.filterNot { isClatAddress(it.address) }
    val order = listOf(ConnectionKind.WIFI, ConnectionKind.ETHERNET)
    val preferred = usable.filter { it.kind in order }.sortedBy { order.indexOf(it.kind) }
    val others = usable.filter { it.kind == ConnectionKind.OTHER }
    return (preferred + others).map { it.address }.distinct()
}

/**
 * The kind of an interface that no network of the device owns, guessed from its name. Only a fallback: names differ by
 * vendor, so this knows the common mobile data and VPN names and calls everything else [ConnectionKind.OTHER].
 */
fun kindOfInterfaceName(name: String): ConnectionKind =
    when {
        CELLULAR_PREFIXES.any { name.startsWith(it) } -> ConnectionKind.CELLULAR
        VPN_PREFIXES.any { name.startsWith(it) } -> ConnectionKind.VPN
        else -> ConnectionKind.OTHER
    }

/** `192.0.0.0/29` is the range of 464XLAT (RFC 7335): the IPv4 side of an IPv6-only mobile network, never on a LAN. */
private fun isClatAddress(address: String): Boolean {
    val parts = address.split('.')
    return parts.size == 4 && parts[0] == "192" && parts[1] == "0" && parts[2] == "0" && (parts[3].toIntOrNull() ?: 8) < 8
}

/** `v4-` and `clat` are the stacked 464XLAT interfaces on top of mobile data, e.g. `v4-rmnet_data0`. */
private val CELLULAR_PREFIXES = listOf("rmnet", "ccmni", "pdp", "rev_rmnet", "v4-", "clat")
private val VPN_PREFIXES = listOf("tun", "ppp", "ipsec", "wg")
