package io.github.tycho991.openframe.core.auth

import io.github.tycho991.openframe.core.platform.Clock
import io.github.tycho991.openframe.core.store.JsonStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

@Serializable
data class StoredClient(
    val id: String,
    val name: String,
    /** SHA-256 of the token, hex. The token itself is never stored. */
    val tokenHash: String,
    val pairedAtMs: Long,
    val lastSeenAtMs: Long,
) {
    val pairedAt: Instant get() = Instant.ofEpochMilli(pairedAtMs)
    val lastSeenAt: Instant get() = Instant.ofEpochMilli(lastSeenAtMs)
}

@Serializable
data class StoredClients(
    val clients: List<StoredClient> = emptyList(),
)

/** Paired devices and their tokens. */
class ClientRegistry(
    private val store: JsonStore<StoredClients>,
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
) {
    private val _clients = MutableStateFlow<List<StoredClient>>(emptyList())

    val clients: StateFlow<List<StoredClient>> = _clients.asStateFlow()

    fun load() {
        _clients.value = store.load()?.clients ?: emptyList()
    }

    /**
     * Creates a client and returns its token. It cannot be retrieved afterwards. The client is
     * published only once it is stored; if storing fails, nothing changes and the exception is passed on.
     */
    @Synchronized
    fun create(name: String): Pair<String, StoredClient> {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))
        val now = clock.millis()
        val id = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val client = StoredClient(id, name, hash(token), now, now)
        update(_clients.value + client, Persist.BEFORE_PUBLISH)
        return token to client
    }

    /** The client for the token or `null`. Remembers when it was last seen (accurate to a minute). */
    fun authenticate(token: String): StoredClient? {
        val h = hash(token)
        val client =
            _clients.value.firstOrNull { MessageDigest.isEqual(it.tokenHash.toByteArray(), h.toByteArray()) } ?: return null
        val now = clock.millis()
        if (now - client.lastSeenAtMs >= LAST_SEEN_RESOLUTION.toMillis()) {
            synchronized(this) {
                update(_clients.value.map { if (it.id == client.id) it.copy(lastSeenAtMs = now) else it }, Persist.LAZY)
            }
        }
        return client
    }

    /** Revokes a pairing. The token becomes invalid immediately, even if storing fails. */
    @Synchronized
    fun remove(id: String): Boolean {
        val remaining = _clients.value.filterNot { it.id == id }
        if (remaining.size == _clients.value.size) return false
        update(remaining, Persist.AFTER_PUBLISH)
        return true
    }

    /** Renames a paired device. Returns the renamed client or `null` for an unknown [id]. */
    @Synchronized
    fun rename(
        id: String,
        name: String,
    ): StoredClient? {
        val client = _clients.value.firstOrNull { it.id == id } ?: return null
        val renamed = client.copy(name = name)
        update(_clients.value.map { if (it.id == id) renamed else it }, Persist.BEFORE_PUBLISH)
        return renamed
    }

    @Synchronized
    fun removeAll() {
        update(emptyList(), Persist.AFTER_PUBLISH)
    }

    suspend fun flush() = store.flush()

    /** When a change is written relative to the moment it takes effect. */
    private enum class Persist {
        /** Written later by the store's writer. */
        LAZY,

        /** Written first; takes effect only if that worked, otherwise nothing changes and the error is thrown. */
        BEFORE_PUBLISH,

        /** Takes effect first (a revoked token must not stay valid); a failed write is retried by the store. */
        AFTER_PUBLISH,
    }

    private fun update(
        clients: List<StoredClient>,
        persist: Persist,
    ) {
        val previous = _clients.value
        when (persist) {
            Persist.LAZY -> {
                _clients.value = clients
                store.save(StoredClients(clients))
            }

            Persist.BEFORE_PUBLISH -> {
                store.save(StoredClients(clients))
                try {
                    kotlinx.coroutines.runBlocking { store.flush() }
                } catch (e: Exception) {
                    // The store keeps the failed state queued; queue the one that is still valid instead.
                    store.save(StoredClients(previous))
                    throw e
                }
                _clients.value = clients
            }

            Persist.AFTER_PUBLISH -> {
                _clients.value = clients
                store.save(StoredClients(clients))
                try {
                    kotlinx.coroutines.runBlocking { store.flush() }
                } catch (e: Exception) {
                    // Stays queued and is retried by the store's writer, which logs the failure.
                }
            }
        }
    }

    companion object {
        const val TOKEN_BYTES = 32
        private val LAST_SEEN_RESOLUTION: Duration = Duration.ofMinutes(1)

        fun hash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
