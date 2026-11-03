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
 * header. The signature is valid for one photo only and expires at the full
 * hour after next, that is, after one to two hours. Within an hour it stays
 * the same so that the browser cache works.
 *
 * Format of `t`: `<expiry in epoch seconds>.<HMAC-SHA256(photoId + "." + expiry), Base64url>`.
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

    /** New key: all signatures issued so far become invalid. */
    @Synchronized
    fun rotate() {
        key = ByteArray(KEY_BYTES).also(random::nextBytes)
        runBlocking { store.saveNow(stored()) }
    }

    private fun stored() = StoredSecret(Base64.getUrlEncoder().withoutPadding().encodeToString(key))

    fun sign(photoId: String): String {
        val nowSeconds = clock.millis() / 1000
        val expires = (nowSeconds / HOUR) * HOUR + 2 * HOUR
        return "$expires.${mac(photoId, expires)}"
    }

    fun verify(
        photoId: String,
        signature: String,
    ): Boolean {
        val expires = signature.substringBefore('.', "").toLongOrNull() ?: return false
        val mac = signature.substringAfter('.', "")
        val nowSeconds = clock.millis() / 1000
        if (expires <= nowSeconds || expires > nowSeconds + 2 * HOUR) return false
        return MessageDigest.isEqual(mac.toByteArray(), mac(photoId, expires).toByteArray())
    }

    private fun mac(
        photoId: String,
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
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac.doFinal("$photoId.$expires".toByteArray()))
    }

    private companion object {
        const val KEY_BYTES = 32
        const val HOUR = 3600L
    }
}
