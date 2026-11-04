package io.github.tycho991.openframe.core.identity

import io.github.tycho991.openframe.core.store.JsonStore
import kotlinx.serialization.Serializable
import java.security.SecureRandom

/** The frame's immutable identity. The name is a setting. */
@Serializable
data class FrameIdentity(
    val id: String,
) {
    companion object {
        /**
         * Loads the ID or, on first start, creates a random one and stores it immediately. If that fails, for example
         * on a full disk, the ID is queued for writing instead, so the start goes on.
         */
        suspend fun loadOrCreate(
            store: JsonStore<FrameIdentity>,
            random: SecureRandom = SecureRandom(),
        ): FrameIdentity {
            store.load()?.let { return it }
            val bytes = ByteArray(16).also(random::nextBytes)
            val identity = FrameIdentity(bytes.joinToString("") { "%02x".format(it) })
            try {
                store.saveNow(identity)
            } catch (e: Exception) {
                store.save(identity)
            }
            return identity
        }
    }
}
