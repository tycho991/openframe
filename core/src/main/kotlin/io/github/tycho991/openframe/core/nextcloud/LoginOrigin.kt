package io.github.tycho991.openframe.core.nextcloud

import okhttp3.HttpUrl

/**
 * Which addresses a login flow may use (spec `nextcloud-source`, "No downgrade during the login"): `http` or `https`
 * on the host of the server, and with an `https` server only `https` on the same port. Pure, so that it is tested
 * without a server.
 */
object LoginOrigin {
    /** Why [other] is refused for a login at [base], `null` if it is accepted. */
    sealed interface Refusal {
        /** `https` turned into `http`, or another port: the server's protocol settings are the usual cause. */
        data object Downgrade : Refusal

        /** Another host, or a scheme other than `http` and `https`. */
        data object Elsewhere : Refusal
    }

    fun refusal(
        base: HttpUrl,
        other: HttpUrl?,
    ): Refusal? {
        if (other == null || other.host != base.host) return Refusal.Elsewhere
        if (other.scheme != "http" && other.scheme != "https") return Refusal.Elsewhere
        if (base.isHttps && (!other.isHttps || other.port != base.port)) return Refusal.Downgrade
        return null
    }

    fun accepts(
        base: HttpUrl,
        other: HttpUrl?,
    ): Boolean = refusal(base, other) == null
}
