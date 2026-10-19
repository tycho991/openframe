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

    /** Creates a client and returns its token. It cannot be retrieved afterwards. */
    @Synchronized
    fun create(name: String): Pair<String, StoredClient> {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))
        val now = clock.millis()
        val id = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val client = StoredClient(id, name, hash(token), now, now)
        update(_clients.value + client, immediately = true)
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
                update(_clients.value.map { if (it.id == client.id) it.copy(lastSeenAtMs = now) else it }, immediately = false)
            }
        }
        return client
    }

    /** Revokes a pairing. The token becomes invalid immediately. */
    @Synchronized
    fun remove(id: String): Boolean {
        val remaining = _clients.value.filterNot { it.id == id }
        if (remaining.size == _clients.value.size) return false
        update(remaining, immediately = true)
        return true
    }

    @Synchronized
    fun removeAll() {
        update(emptyList(), immediately = true)
    }

    suspend fun flush() = store.flush()

    private fun update(
        clients: List<StoredClient>,
        immediately: Boolean,
    ) {
        _clients.value = clients
        store.save(StoredClients(clients))
        // Pairings and revocations are meant to survive a restart reliably.
        if (immediately) kotlinx.coroutines.runBlocking { store.flush() }
    }

    companion object {
        const val TOKEN_BYTES = 32
        private val LAST_SEEN_RESOLUTION: Duration = Duration.ofMinutes(1)

        fun hash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
