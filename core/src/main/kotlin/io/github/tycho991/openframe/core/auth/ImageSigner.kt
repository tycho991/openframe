package io.github.tycho991.openframe.core.auth

import io.github.tycho991.openframe.core.platform.Clock
import io.github.tycho991.openframe.core.store.JsonStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Serializable
data class StoredSecret(
    /** Base64url of the HMAC key for image signatures. */
    val imageKey: String,
)

/**
 * Signatures for image URLs, because `<img>` cannot send an Authorization
 * header. The signature is valid for one photo only, names the client it was
 * issued to and expires at the full hour after next, that is, after one to two
 * hours. Within an hour it stays the same per client so that the browser cache
 * works. [AccessControl] also requires that the client is still paired.
 *
 * Format of `t`: `<expiry in epoch seconds>.<client ID>.<HMAC-SHA256(photoId.clientId.expiry), Base64url>`.
 */
class ImageSigner(
    private val store: JsonStore<StoredSecret>,
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
) {
    @Volatile
    private var key: ByteArray = ByteArray(0)

    /** One initialized HMAC per thread and key, because a photo list signs a hundred URLs at once. */
    private val macs = ThreadLocal<Pair<ByteArray, Mac>>()

    /**
     * Loads the key, or creates a new one at the first start or when `secret.json` cannot be read. A new key that
     * cannot be written at once, for example on a full disk, is queued for writing, so the start goes on.
     */
    @Synchronized
    fun load() {
        val stored = store.load()?.let { runCatching { Base64.getUrlDecoder().decode(it.imageKey) }.getOrNull() }
        if (stored != null) {
            key = stored
            return
        }
        key = ByteArray(KEY_BYTES).also(random::nextBytes)
        try {
            runBlocking { store.saveNow(stored()) }
        } catch (e: Exception) {
            store.save(stored())
        }
    }

    /**
     * New key: all signatures issued so far become invalid at once. Stored with [JsonStore.saveCritical], so that no
     * earlier signature is valid after a restart, even on a full disk; if even that fails, the key is queued.
     */
    @Synchronized
    fun rotate() {
        key = ByteArray(KEY_BYTES).also(random::nextBytes)
        try {
            runBlocking { store.saveCritical(stored()) }
        } catch (e: Exception) {
            store.save(stored())
        }
    }

    private fun stored() = StoredSecret(Base64.getUrlEncoder().withoutPadding().encodeToString(key))

    fun sign(
        photoId: String,
        clientId: String,
    ): String {
        require(CLIENT_ID.matches(clientId)) { "Client IDs are 16 hex characters" }
        val nowSeconds = clock.millis() / 1000
        val expires = (nowSeconds / HOUR) * HOUR + 2 * HOUR
        return "$expires.$clientId.${mac(photoId, clientId, expires)}"
    }

    /** The client the valid [signature] for [photoId] was issued to, or `null`. */
    fun verify(
        photoId: String,
        signature: String,
    ): String? {
        val parts = signature.split('.')
        if (parts.size != 3) return null
        val expires = parts[0].toLongOrNull() ?: return null
        val clientId = parts[1].takeIf { CLIENT_ID.matches(it) } ?: return null
        val nowSeconds = clock.millis() / 1000
        if (expires <= nowSeconds || expires > nowSeconds + 2 * HOUR) return null
        val valid = MessageDigest.isEqual(parts[2].toByteArray(), mac(photoId, clientId, expires).toByteArray())
        return if (valid) clientId else null
    }

    private fun mac(
        photoId: String,
        clientId: String,
        expires: Long,
    ): String {
        val k = key
        val cached = macs.get()
        val hmac =
            if (cached != null && cached.first === k) {
                cached.second
            } else {
                Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(k, "HmacSHA256")) }.also { macs.set(k to it) }
            }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac.doFinal("$photoId.$clientId.$expires".toByteArray()))
    }

    private companion object {
        const val KEY_BYTES = 32
        const val HOUR = 3600L
        val CLIENT_ID = Regex("[0-9a-f]{16}")
    }
}
