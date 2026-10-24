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
    val order = listOf(ConnectionKind.WIFI, ConnectionKind.ETHERNET)
    val preferred = candidates.filter { it.kind in order }.sortedBy { order.indexOf(it.kind) }
    val others = candidates.filter { it.kind == ConnectionKind.OTHER }
    return (preferred + others).map { it.address }.distinct()
}
