package io.github.tycho991.openframe.core.http

import io.github.tycho991.openframe.core.platform.Clock
import io.github.tycho991.openframe.core.platform.LocalAddresses
import java.net.InetAddress

/**
 * Decides on which of the device's own addresses a connection may arrive (spec `admin-access`, "Connections only on
 * local addresses"; finish-lan-hardening, design decisions 2 and 3). Loopback is always accepted. The other addresses
 * come from [read]; the answer is kept until [clear] (a network change) or until a connection arrives on an address
 * that is neither accepted nor refused, which reads them again at once, at most every [refreshMs].
 *
 * Addresses are compared by their bytes, never parsed back from text: IPv4-mapped IPv6 addresses, the form in which a
 * dual-stack socket reports IPv4 clients, count as their IPv4 address, and the scope of a link-local address is
 * ignored. The accept thread calls [accepts]; the snapshot is immutable and published through a volatile field.
 */
class LocalAddressFilter(
    private val clock: Clock,
    private val read: () -> LocalAddresses,
    private val refreshMs: Long = REFRESH_MS,
) {
    private class Snapshot(
        val accepted: Set<Key>,
        val refused: Set<Key>,
        val at: Long,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    fun accepts(address: InetAddress): Boolean {
        val key = Key.of(address)
        if (key.isLoopback) return true
        var snap = snapshot ?: refresh()
        if (key in snap.accepted) return true
        if (key in snap.refused) return false
        snap = refresh()
        return key in snap.accepted
    }

    /** After a network change: the next connection reads the addresses again. */
    fun clear() {
        snapshot = null
    }

    /** Reads the addresses, unless the last read is younger than [refreshMs]; a cleared snapshot is read at once. */
    @Synchronized
    private fun refresh(): Snapshot {
        val current = snapshot
        val now = clock.monotonicMillis()
        if (current != null && now - current.at < refreshMs) return current
        val addresses = runCatching(read).getOrDefault(LocalAddresses.NONE)
        return Snapshot(
            addresses.accepted.map(Key::of).toSet(),
            addresses.refused.map(Key::of).toSet(),
            now,
        ).also { snapshot = it }
    }

    /** The bytes of an address, IPv4-mapped IPv6 as IPv4. */
    private class Key private constructor(
        private val bytes: List<Byte>,
    ) {
        val isLoopback: Boolean
            get() = (bytes.size == 4 && bytes[0] == 127.toByte()) || (bytes.size == 16 && bytes == IPV6_LOOPBACK)

        override fun equals(other: Any?) = other is Key && other.bytes == bytes

        override fun hashCode() = bytes.hashCode()

        companion object {
            private val IPV6_LOOPBACK = List(15) { 0.toByte() } + 1.toByte()
            private val MAPPED_PREFIX = List(10) { 0.toByte() } + listOf(0xff.toByte(), 0xff.toByte())

            fun of(address: InetAddress): Key {
                val raw = address.address.toList()
                return Key(if (raw.size == 16 && raw.subList(0, 12) == MAPPED_PREFIX) raw.subList(12, 16) else raw)
            }
        }
    }

    private companion object {
        const val REFRESH_MS = 1_000L
    }
}
