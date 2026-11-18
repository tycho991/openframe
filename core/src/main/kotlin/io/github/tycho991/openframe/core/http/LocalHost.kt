package io.github.tycho991.openframe.core.http

/**
 * The `Host` of a request split into name and port, or `null` if it is not a valid host with optional port: a DNS name
 * or an IPv4 address, or an IPv6 address in brackets. The name is in lower case and without a trailing dot.
 */
internal class ParsedHost(
    val name: String,
    val ipv6: Boolean,
)

private val LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
private val IPV6 = Regex("[0-9a-f:.]+(?:%25[a-z0-9._~-]+)?")
private val PORT = Regex("[0-9]{1,5}")

/** Suffixes of names that only resolve on the local network (RFC 6762 appendix G, RFC 8375 and FRITZ!Box). */
private val LOCAL_SUFFIXES =
    listOf(".local", ".home.arpa", ".localdomain", ".intranet", ".internal", ".private", ".corp", ".home", ".lan", ".fritz.box")

internal fun parseHost(host: String): ParsedHost? {
    if (host.isEmpty() || host.length > MAX_HOST) return null
    val lower = host.lowercase()
    val name: String
    val port: String?
    if (lower.startsWith("[")) {
        val end = lower.indexOf(']')
        if (end < 0) return null
        name = lower.substring(1, end)
        val rest = lower.substring(end + 1)
        port =
            if (rest.isEmpty()) {
                null
            } else if (rest.startsWith(":")) {
                rest.substring(1)
            } else {
                return null
            }
        if (!IPV6.matches(name) || ':' !in name) return null
    } else {
        val colon = lower.lastIndexOf(':')
        name = if (colon < 0) lower else lower.substring(0, colon)
        port = if (colon < 0) null else lower.substring(colon + 1)
    }
    if (port != null && (!PORT.matches(port) || port.toInt() !in 1..65535)) return null
    if (lower.startsWith("[")) return ParsedHost(name, ipv6 = true)
    val bare = name.removeSuffix(".")
    if (bare.isEmpty() || bare.split('.').any { !LABEL.matches(it) }) return null
    return ParsedHost(bare, ipv6 = false)
}

private fun isIpv4(name: String): Boolean {
    val parts = name.split('.')
    return parts.size == 4 && parts.all { it.length in 1..3 && it.all(Char::isDigit) && it.toInt() <= 255 }
}

/**
 * Whether [host], the `Host` header of a request, names the frame on the local network: an IP address, `localhost`,
 * a single-label name, or a name under one of the local suffixes, each with an optional port. A request without `Host`
 * is allowed, because a browser always sends one. The name is never resolved: against DNS rebinding the lookup is the
 * very thing an attacker controls (design decision 4 of harden-lan-server).
 */
fun isLocalHost(host: String?): Boolean {
    if (host == null) return true
    val parsed = parseHost(host) ?: return false
    val name = parsed.name
    return parsed.ipv6 || isIpv4(name) || '.' !in name || LOCAL_SUFFIXES.any { name.endsWith(it) }
}

private const val MAX_HOST = 260
