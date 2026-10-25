package io.github.tycho991.openframe.core.nextcloud

import java.net.URI
import java.net.URISyntaxException

/** The link of a Nextcloud share taken apart: the server it is on and the token that names the share. */
data class ShareLink(
    /** Base address of the server without a trailing `/`, e.g. `https://cloud.example.org` or `http://nas/nextcloud`. */
    val serverUrl: String,
    val token: String,
) {
    /** The token is a capability: it stays out of every text. */
    override fun toString() = "ShareLink(server=$serverUrl)"

    companion object {
        private val TOKEN = Regex("[A-Za-z0-9]+")

        /**
         * `http(s)://host[:port][/path][/index.php]/s/<token>[/]`; a query or fragment after it (Nextcloud adds `?dir=/`
         * at times) is ignored. `null` for anything else: another scheme, no host, user info, no `/s/<token>` at the end
         * or a token with other characters than letters and digits.
         */
        fun parse(raw: String): ShareLink? {
            val uri =
                try {
                    URI(raw.trim())
                } catch (e: URISyntaxException) {
                    return null
                }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host
            if (host.isNullOrEmpty() || uri.rawUserInfo != null) return null
            val segments =
                uri.rawPath
                    .orEmpty()
                    .split('/')
                    .filter { it.isNotEmpty() }
            if (segments.size < 2 || segments[segments.size - 2] != "s") return null
            val token = segments.last()
            if (!TOKEN.matches(token)) return null
            val prefix = segments.dropLast(2).let { if (it.lastOrNull() == "index.php") it.dropLast(1) else it }
            val port = if (uri.port == -1) "" else ":${uri.port}"
            val path = prefix.joinToString("") { "/$it" }
            return ShareLink("$scheme://${host.lowercase()}$port$path", token)
        }
    }
}
