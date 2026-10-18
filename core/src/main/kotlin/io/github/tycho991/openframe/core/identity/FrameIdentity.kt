package io.github.tycho991.openframe.core.identity

import io.github.tycho991.openframe.core.store.JsonStore
import kotlinx.serialization.Serializable
import java.security.SecureRandom

/** Die unveränderliche Identität des Rahmens. Der Name ist eine Einstellung. */
@Serializable
data class FrameIdentity(
    val id: String,
) {
    companion object {
        /** Lädt die ID oder legt beim ersten Start eine zufällige an und speichert sie sofort. */
        suspend fun loadOrCreate(
            store: JsonStore<FrameIdentity>,
            random: SecureRandom = SecureRandom(),
        ): FrameIdentity {
            store.load()?.let { return it }
            val bytes = ByteArray(16).also(random::nextBytes)
            val identity = FrameIdentity(bytes.joinToString("") { "%02x".format(it) })
            store.saveNow(identity)
            return identity
        }
    }
}
