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
    /** Base64url des HMAC-Schlüssels für Bild-Signaturen. */
    val imageKey: String,
)

/**
 * Signaturen für Bild-URLs, weil `<img>` keinen Authorization-Header schicken
 * kann. Die Signatur gilt nur für ein Foto und läuft zur übernächsten vollen
 * Stunde ab, also nach ein bis zwei Stunden. Innerhalb einer Stunde ist sie
 * gleich, damit der Browser-Cache greift.
 *
 * Format von `t`: `<Ablauf in Epoch-Sekunden>.<HMAC-SHA256(photoId + "." + Ablauf), Base64url>`.
 */
class ImageSigner(
    private val store: JsonStore<StoredSecret>,
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
) {
    @Volatile
    private var key: ByteArray = ByteArray(0)

    /** Ein initialisiertes HMAC pro Thread und Schlüssel, weil eine Fotoliste hundert URLs auf einmal signiert. */
    private val macs = ThreadLocal<Pair<ByteArray, Mac>>()

    fun load() {
        val stored = store.load()
        if (stored == null) rotate() else key = Base64.getUrlDecoder().decode(stored.imageKey)
    }

    /** Neuer Schlüssel: Alle bisher ausgestellten Signaturen werden ungültig. */
    @Synchronized
    fun rotate() {
        key = ByteArray(KEY_BYTES).also(random::nextBytes)
        runBlocking { store.saveNow(StoredSecret(Base64.getUrlEncoder().withoutPadding().encodeToString(key))) }
    }

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
