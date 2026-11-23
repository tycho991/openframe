package io.github.tycho991.openframe.core.sources

import io.github.tycho991.openframe.core.platform.Clock

/**
 * The proof for widening an account folder (spec `remote-sources`, "Proof for widening an account folder"; design
 * decision 14 of `paired-device-guards`): a device that completed a login for exactly this account, adding it, renewing
 * its credentials or confirming it, may widen its folder for [VALIDITY_MS]. In memory only, on the monotonic clock;
 * [NextcloudConnector][io.github.tycho991.openframe.core.nextcloud.NextcloudConnector] writes it and [SourceRegistry]
 * reads it. A grant of a device that is no longer paired counts for nothing and is dropped when touched.
 */
class AccountGrants(
    private val clock: Clock,
    /** Whether a client is still paired. */
    private val paired: (String) -> Boolean = { true },
) {
    private val grants = HashMap<Pair<String, String>, Long>()

    /** [clientId] completed a login for the account of [sourceId] just now. */
    @Synchronized
    fun grant(
        clientId: String,
        sourceId: String,
    ) {
        grants[clientId to sourceId] = clock.monotonicMillis()
    }

    /** Whether [clientId] may widen the folder of [sourceId] now. */
    @Synchronized
    fun has(
        clientId: String?,
        sourceId: String,
    ): Boolean {
        if (clientId == null) return false
        val key = clientId to sourceId
        val at = grants[key] ?: return false
        val valid = clock.monotonicMillis() - at < VALIDITY_MS && paired(clientId)
        if (!valid) grants.remove(key)
        return valid
    }

    /** The source is gone: so are its grants. */
    @Synchronized
    fun sourceRemoved(sourceId: String) {
        grants.keys.removeAll { it.second == sourceId }
    }

    companion object {
        const val VALIDITY_MS = 30 * 60_000L
    }
}
