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
import java.util.Base64

/**
 * What the frame should show for pairing.
 *
 * [code] and [url] are set as long as somebody needs them: the frame itself
 * (empty state, hint) or a request from the web UI. [url] is the pairing
 * link with the secret for the QR code, `null` without a network address.
 * While pairing by code is locked, [code] is `null` and [codeLocked] is set,
 * the QR code stays. [requestedUntil] is set as long as the hint stays up
 * because of such a request.
 */
data class PairingDisplay(
    val code: String?,
    val url: String?,
    val codeLocked: Boolean,
    val requestedUntil: Instant?,
)

/** An invitation as `POST /api/v1/pairing/invites` returns it. */
data class CreatedInvite(
    val url: String,
    val expiresAt: Instant,
)

/**
 * Pairing sessions and invitations.
 *
 * A session consists of a six-digit code and a secret for the QR code. Both
 * are created together, are valid for 5 minutes and usable only once, and
 * redeeming either ends the session. After 5 wrong codes only the code is
 * discarded and pairing by code is locked for 60 seconds; the secret stays.
 * Wrong secrets do not count as failed attempts. If a displayed session
 * expires, or the lockout ends, [tick] replaces it with a new one.
 *
 * An invitation is a secret of its own, created by a paired client, valid for
 * 5 minutes and once. Each client has at most one, and it ends with the
 * client's pairing. Invitations live in memory only.
 *
 * [adminUrl] is the address of the admin interface, e.g. `http://192.168.100.215:8080/`.
 */
class PairingManager(
    private val clients: ClientRegistry,
    private val clock: Clock,
    private val log: AppLog,
    private val adminUrl: () -> String?,
    private val random: SecureRandom = SecureRandom(),
) {
    private class Invite(
        val secret: String,
        val expiresAt: Instant,
    )

    private var code: String? = null
    private var secret: String? = null
    private var expiresAt: Instant = Instant.EPOCH
    private var lastExpiredCode: String? = null
    private var lastExpiredSecret: String? = null
    private var failures = 0
    private var lockedUntil: Instant = Instant.EPOCH
    private var requestedUntil: Instant? = null
    private var shownOnFrame = false
    private val invites = HashMap<String, Invite>()

    private val _display = MutableStateFlow(PairingDisplay(null, null, false, null))

    val display: StateFlow<PairingDisplay> = _display.asStateFlow()

    /** The frame is currently showing pairing (empty state or hint) and needs a valid session. */
    @Synchronized
    fun setShownOnFrame(shown: Boolean) {
        shownOnFrame = shown
        refresh()
    }

    /**
     * `POST /api/v1/pairing/code`: the frame shows a valid code and QR code for
     * 5 minutes. During the lockout it shows the QR code alone.
     */
    @Synchronized
    fun requestDisplay() {
        requestedUntil = clock.now() + REQUEST_WINDOW
        refresh()
    }

    /** Once per second: replace expired sessions, close the request window. */
    @Synchronized
    fun tick() = refresh()

    /** Checks the six-digit [input] and creates a client on success. Returns token and client. */
    @Synchronized
    fun pair(
        input: String,
        deviceName: String,
    ): Pair<String, StoredClient> {
        val now = clock.now()
        if (now.isBefore(lockedUntil)) throw locked(now)
        expireIfDue(now)
        val current = code
        if (current != null && equal(input, current)) return completeSession(deviceName)
        val expired = lastExpiredCode
        if (expired != null && equal(input, expired)) throw expired()
        // Without a live code there is nothing to guess, so such attempts must not lock out the next session.
        if (current == null) throw invalid()
        failures++
        if (failures >= MAX_FAILURES) {
            log.w(TAG, "$MAX_FAILURES wrong codes, pairing by code locked for ${LOCK.seconds} s")
            failures = 0
            code = null
            lastExpiredCode = null
            lockedUntil = now + LOCK
            refresh()
        }
        throw invalid()
    }

    /**
     * Checks a secret from the frame's QR code or from an invitation and creates
     * a client on success. Not subject to the lockout, and a wrong secret is no
     * failed attempt.
     */
    @Synchronized
    fun pairWithSecret(
        input: String,
        deviceName: String,
    ): Pair<String, StoredClient> {
        val now = clock.now()
        expireIfDue(now)
        val current = secret
        if (current != null && equal(input, current)) return completeSession(deviceName)
        val invite = invites.entries.firstOrNull { equal(input, it.value.secret) }
        if (invite != null) {
            invites.remove(invite.key)
            if (clients.clients.value.none { it.id == invite.key }) throw invalid()
            if (!now.isBefore(invite.value.expiresAt)) throw expired()
            val result = clients.create(deviceName)
            log.i(TAG, "Paired by invitation: ${result.second.name}")
            return result
        }
        val expired = lastExpiredSecret
        if (expired != null && equal(input, expired)) throw expired()
        throw invalid()
    }

    /**
     * Creates an invitation for the paired client [clientId], replacing its open
     * one. [baseUrl] is the admin address the link points to, ending with `/`.
     */
    @Synchronized
    fun createInvite(
        clientId: String,
        baseUrl: String,
    ): CreatedInvite {
        val invite = Invite(newSecret(), clock.now() + VALIDITY)
        invites[clientId] = invite
        return CreatedInvite("$baseUrl#pair=${invite.secret}", invite.expiresAt)
    }

    /** The pairing of [clientId] was revoked: its invitation ends. */
    @Synchronized
    fun dropInvite(clientId: String) {
        invites.remove(clientId)
    }

    /** Discards session and invitations, e.g. after "Revoke all pairings", and shows a new session. */
    @Synchronized
    fun reset() {
        code = null
        secret = null
        lastExpiredCode = null
        lastExpiredSecret = null
        failures = 0
        lockedUntil = Instant.EPOCH
        invites.clear()
        requestedUntil = clock.now() + REQUEST_WINDOW
        refresh()
    }

    private fun completeSession(deviceName: String): Pair<String, StoredClient> {
        code = null
        secret = null
        failures = 0
        requestedUntil = null
        refresh()
        val result = clients.create(deviceName)
        log.i(TAG, "Paired: ${result.second.name}")
        return result
    }

    private fun refresh() {
        val now = clock.now()
        expireIfDue(now)
        if (requestedUntil != null && !now.isBefore(requestedUntil)) requestedUntil = null
        invites.values.removeAll { !now.isBefore(it.expiresAt + VALIDITY) }
        val needed = shownOnFrame || requestedUntil != null
        val locked = now.isBefore(lockedUntil)
        // A session without a code after the lockout is replaced by a complete one.
        if (needed && (secret == null || (code == null && !locked))) {
            if (secret != null) expire()
            secret = newSecret()
            failures = 0
            code = if (locked) null else (0 until 6).map { random.nextInt(10) }.joinToString("")
            expiresAt = now + VALIDITY
        }
        val url = if (needed) secret?.let { s -> adminUrl()?.let { "$it#pair=$s" } } else null
        _display.value =
            PairingDisplay(
                code = if (needed && !locked) code else null,
                url = url,
                codeLocked = needed && locked,
                requestedUntil = requestedUntil,
            )
    }

    private fun expireIfDue(now: Instant) {
        if (secret != null && !now.isBefore(expiresAt)) expire()
    }

    private fun expire() {
        failures = 0
        lastExpiredCode = code
        lastExpiredSecret = secret
        code = null
        secret = null
    }

    private fun newSecret(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(SECRET_BYTES).also(random::nextBytes))

    private fun invalid() = HttpException(401, "invalid_code", "The code is wrong.")

    private fun expired() = HttpException(401, "expired_code", "The code has expired. Have the frame show a new one.")

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
        val VALIDITY: Duration = Duration.ofMinutes(5)
        val REQUEST_WINDOW: Duration = Duration.ofMinutes(5)
        val LOCK: Duration = Duration.ofSeconds(60)
        const val MAX_FAILURES = 5
        const val SECRET_BYTES = 16
        private const val TAG = "pairing"
    }
}
