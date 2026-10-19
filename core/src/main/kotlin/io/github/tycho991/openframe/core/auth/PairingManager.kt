package io.github.tycho991.openframe.core.auth

import io.github.tycho991.openframe.core.http.HttpException
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

/**
 * What the frame should show for pairing.
 *
 * [code] is the valid code as long as somebody needs it: the frame itself
 * (empty state, hint) or a request from the web UI. [requestedUntil]
 * is set as long as the hint stays up because of such a request.
 */
data class PairingDisplay(
    val code: String?,
    val requestedUntil: Instant?,
)

/**
 * Pairing codes: six digits, valid for 5 minutes, usable only once.
 * After 5 failed attempts the code is discarded and pairing is locked for 60 seconds.
 * If a displayed code expires, [tick] replaces it with a new one.
 */
class PairingManager(
    private val clients: ClientRegistry,
    private val clock: Clock,
    private val log: AppLog,
    private val random: SecureRandom = SecureRandom(),
) {
    private var code: String? = null
    private var expiresAt: Instant = Instant.EPOCH
    private var lastExpiredCode: String? = null
    private var failures = 0
    private var lockedUntil: Instant = Instant.EPOCH
    private var requestedUntil: Instant? = null
    private var shownOnFrame = false

    private val _display = MutableStateFlow(PairingDisplay(null, null))

    val display: StateFlow<PairingDisplay> = _display.asStateFlow()

    /** The frame is currently showing a code (empty state or hint) and needs a valid one. */
    @Synchronized
    fun setShownOnFrame(shown: Boolean) {
        shownOnFrame = shown
        refresh()
    }

    /** `POST /api/v1/pairing/code`: the frame shows a valid code for 5 minutes. */
    @Synchronized
    fun requestDisplay() {
        val now = clock.now()
        if (now.isBefore(lockedUntil)) throw locked(now)
        requestedUntil = now + REQUEST_WINDOW
        refresh()
    }

    /** Once per second: replace expired codes, close the request window. */
    @Synchronized
    fun tick() = refresh()

    /** Checks [input] and creates a client on success. Returns token and client. */
    @Synchronized
    fun pair(
        input: String,
        deviceName: String,
    ): Pair<String, StoredClient> {
        val now = clock.now()
        if (now.isBefore(lockedUntil)) throw locked(now)
        val current = code
        if (current != null && !now.isBefore(expiresAt)) expire()
        if (code != null && equal(input, code!!)) {
            code = null
            failures = 0
            requestedUntil = null
            refresh()
            val result = clients.create(deviceName)
            log.i(TAG, "Paired: ${result.second.name}")
            return result
        }
        if (lastExpiredCode != null && equal(input, lastExpiredCode!!)) {
            throw HttpException(401, "expired_code", "The code has expired. Have the frame show a new one.")
        }
        failures++
        if (failures >= MAX_FAILURES) {
            log.w(TAG, "$MAX_FAILURES wrong codes, pairing locked for ${LOCK.seconds} s")
            failures = 0
            code = null
            lastExpiredCode = null
            lockedUntil = now + LOCK
            refresh()
        }
        throw HttpException(401, "invalid_code", "The code is wrong.")
    }

    /** Discards the current code, e.g. after "Revoke all pairings", and shows a new one. */
    @Synchronized
    fun reset() {
        code = null
        lastExpiredCode = null
        failures = 0
        lockedUntil = Instant.EPOCH
        requestedUntil = clock.now() + REQUEST_WINDOW
        refresh()
    }

    private fun refresh() {
        val now = clock.now()
        if (code != null && !now.isBefore(expiresAt)) expire()
        if (requestedUntil != null && !now.isBefore(requestedUntil)) requestedUntil = null
        val needed = shownOnFrame || requestedUntil != null
        val locked = now.isBefore(lockedUntil)
        if (needed && !locked && code == null) {
            code = (0 until 6).map { random.nextInt(10) }.joinToString("")
            expiresAt = now + CODE_VALIDITY
        }
        _display.value = PairingDisplay(if (needed && !locked) code else null, requestedUntil)
    }

    private fun expire() {
        lastExpiredCode = code
        code = null
    }

    private fun locked(now: Instant): HttpException {
        val seconds = Duration.between(now, lockedUntil).seconds.coerceAtLeast(1)
        return HttpException(
            429,
            "locked",
            "Too many failed attempts. Try again in $seconds s.",
            headers = mapOf("Retry-After" to seconds.toString()),
        )
    }

    private fun equal(
        a: String,
        b: String,
    ) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object {
        val CODE_VALIDITY: Duration = Duration.ofMinutes(5)
        val REQUEST_WINDOW: Duration = Duration.ofMinutes(5)
        val LOCK: Duration = Duration.ofSeconds(60)
        const val MAX_FAILURES = 5
        private const val TAG = "pairing"
    }
}
