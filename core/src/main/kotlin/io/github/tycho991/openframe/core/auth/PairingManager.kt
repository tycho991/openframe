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
import java.util.ArrayDeque
import java.util.Base64

/**
 * What the frame should show for pairing.
 *
 * [code] and [url] are set as long as somebody needs them: the frame itself
 * (empty state, hint) or a request from the web UI. [url] is the pairing
 * link with the secret for the QR code, `null` without a network address.
 * While pairing by code is locked or paused, [code] is `null` and [codeLocked]
 * is set, the QR code stays. [requested] is set as long as the hint stays up
 * because of such a request.
 */
data class PairingDisplay(
    val code: String?,
    val url: String?,
    val codeLocked: Boolean,
    val requested: Boolean,
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
 * redeeming either ends the session. A code is created only while the frame
 * shows the pairing and its screen is usable (on, not in a dark wake); a
 * request from the web UI alone creates only the secret, which cannot be
 * guessed. A code once created stays valid for its 5 minutes.
 *
 * After 5 wrong codes only the code is discarded and pairing by code is locked,
 * for 60 seconds at first and twice as long at each further lockout, at most an
 * hour; the secret stays. After 20 wrong codes within 24 hours pairing by code
 * pauses until the oldest of them is 24 hours old. A successful pairing of any
 * kind and [reset] end lockout and pause. Wrong secrets do not count as failed
 * attempts. If a displayed session expires, or the lockout ends, [tick]
 * replaces it with a new one.
 *
 * An invitation is a secret of its own, created by a paired client, valid for
 * 5 minutes and once. Each client has at most one, and it ends with the
 * client's pairing. Invitations, lockout and budget live in memory only.
 *
 * All deadlines run on [Clock.monotonicMillis], so that setting the device's
 * clock moves none of them. Creating the client, which writes to storage, runs
 * outside the lock.
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
        val expiresAt: Long,
    )

    private var code: String? = null
    private var secret: String? = null
    private var expiresAt = 0L
    private var lastExpiredCode: String? = null
    private var lastExpiredSecret: String? = null
    private var failures = 0
    private var lockedUntil = NEVER
    private var lockouts = 0
    private val wrongCodes = ArrayDeque<Long>()
    private var requestedUntil: Long? = null
    private var shownOnFrame = false
    private var screenUsable = true
    private val invites = HashMap<String, Invite>()

    private val _display = MutableStateFlow(PairingDisplay(null, null, false, false))

    val display: StateFlow<PairingDisplay> = _display.asStateFlow()

    /** The frame is currently showing pairing (empty state or hint) and needs a valid session. */
    @Synchronized
    fun setShownOnFrame(shown: Boolean) {
        shownOnFrame = shown
        refresh()
    }

    /** Whether someone can see the screen: the display is on and not in a dark wake. `FrameCore` feeds it. */
    @Synchronized
    internal fun setScreenUsable(usable: Boolean) {
        screenUsable = usable
        refresh()
    }

    /**
     * `POST /api/v1/pairing/code`: the frame shows the hint for 5 minutes, with a
     * code once it shows the hint on a usable screen. While pairing by code is
     * locked or paused it shows the QR code alone.
     */
    @Synchronized
    internal fun requestDisplay() {
        requestedUntil = clock.monotonicMillis() + REQUEST_WINDOW.toMillis()
        refresh()
    }

    /** Once per second: replace expired sessions, close the request window. */
    @Synchronized
    internal fun tick() = refresh()

    /** Checks the six-digit [input] and creates a client on success. Returns token and client. Blocks on fsync; never call on the main thread. */
    internal fun pair(
        input: String,
        deviceName: String,
    ): Pair<String, StoredClient> {
        synchronized(this) {
            val now = clock.monotonicMillis()
            if (isPaused(now)) throw pausedError(now)
            if (now < lockedUntil) throw lockedError(now)
            expireIfDue(now)
            val current = code
            if (current == null || !equal(input, current)) {
                val expired = lastExpiredCode
                if (expired != null && equal(input, expired)) throw expiredError()
                // Without a live code there is nothing to guess, so such attempts must not lock out the next session.
                if (current != null) wrongCode(now)
                throw invalidError()
            }
            consumeSession()
        }
        return create(deviceName, PairedVia.CODE, null)
    }

    /**
     * Checks a secret from the frame's QR code or from an invitation and creates
     * a client on success. Not subject to the lockout or the budget, and a wrong
     * secret is no failed attempt. Blocks on fsync; never call on the main thread.
     */
    internal fun pairWithSecret(
        input: String,
        deviceName: String,
    ): Pair<String, StoredClient> {
        // The inviting device's ID for a pairing by invitation, else null.
        val inviter =
            synchronized(this) {
                val now = clock.monotonicMillis()
                expireIfDue(now)
                val current = secret
                if (current != null && equal(input, current)) {
                    consumeSession()
                    return@synchronized null
                }
                val invite = invites.entries.firstOrNull { equal(input, it.value.secret) }
                if (invite == null) {
                    val expired = lastExpiredSecret
                    if (expired != null && equal(input, expired)) throw expiredError()
                    throw invalidError()
                }
                invites.remove(invite.key)
                if (clients.clients.value.none { it.id == invite.key }) throw invalidError()
                if (now >= invite.value.expiresAt) throw expiredError()
                endProtection()
                refresh()
                invite.key
            }
        return create(deviceName, if (inviter == null) PairedVia.QR else PairedVia.INVITE, inviter)
    }

    /**
     * Creates an invitation for the paired client [clientId], replacing its open
     * one. [baseUrl] is the admin address the link points to, ending with `/`.
     */
    @Synchronized
    internal fun createInvite(
        clientId: String,
        baseUrl: String,
    ): CreatedInvite {
        val invite = Invite(newSecret(), clock.monotonicMillis() + VALIDITY.toMillis())
        invites[clientId] = invite
        return CreatedInvite("$baseUrl#pair=${invite.secret}", clock.now() + VALIDITY)
    }

    /** The pairing of [clientId] was revoked: its invitation ends. */
    @Synchronized
    internal fun dropInvite(clientId: String) {
        invites.remove(clientId)
    }

    /** Discards session and invitations, e.g. after "Revoke all pairings", and shows a new session. */
    @Synchronized
    internal fun reset() {
        code = null
        secret = null
        lastExpiredCode = null
        lastExpiredSecret = null
        failures = 0
        endProtection()
        invites.clear()
        requestedUntil = clock.monotonicMillis() + REQUEST_WINDOW.toMillis()
        refresh()
    }

    /** A session was redeemed: it ends, and lockout and budget start over. Called under the lock. */
    private fun consumeSession() {
        code = null
        secret = null
        failures = 0
        requestedUntil = null
        endProtection()
        refresh()
    }

    private fun endProtection() {
        lockedUntil = NEVER
        lockouts = 0
        wrongCodes.clear()
    }

    /**
     * Creates the client outside the lock with its origin [via] and the [inviter] of an invitation, and logs both (spec
     * `admin-access`, "Log of device management" and "Origin of a pairing"). Every pairing passes here.
     */
    private fun create(
        deviceName: String,
        via: String,
        inviter: String?,
    ): Pair<String, StoredClient> {
        val result = clients.create(deviceName, via, inviter)
        val client = result.second
        val origin =
            when (via) {
                PairedVia.CODE -> "with the code"
                PairedVia.QR -> "with the QR code"
                else -> "by invitation of $inviter"
            }
        log.i(TAG, "Paired ${client.id} $origin: ${client.name}")
        return result
    }

    private fun wrongCode(now: Long) {
        failures++
        wrongCodes.addLast(now)
        if (isPaused(now)) {
            log.w(TAG, "$DAILY_BUDGET wrong codes within 24 hours, pairing by code paused")
            discardCode()
        } else if (failures >= MAX_FAILURES) {
            val millis = minOf(LOCK.toMillis() shl minOf(lockouts, MAX_DOUBLINGS), MAX_LOCK.toMillis())
            lockouts++
            lockedUntil = now + millis
            log.w(TAG, "$MAX_FAILURES wrong codes, pairing by code locked for ${millis / 1000} s")
            discardCode()
        }
    }

    private fun discardCode() {
        failures = 0
        code = null
        lastExpiredCode = null
        refresh()
    }

    /** Whether the budget of wrong codes is used up; drops the ones older than 24 hours. */
    private fun isPaused(now: Long): Boolean {
        while (wrongCodes.isNotEmpty() && now - wrongCodes.peekFirst() >= BUDGET_WINDOW.toMillis()) wrongCodes.removeFirst()
        return wrongCodes.size >= DAILY_BUDGET
    }

    private fun refresh() {
        val now = clock.monotonicMillis()
        expireIfDue(now)
        requestedUntil?.let { if (now >= it) requestedUntil = null }
        invites.values.removeAll { now >= it.expiresAt + VALIDITY.toMillis() }
        val needed = shownOnFrame || requestedUntil != null
        val blocked = now < lockedUntil || isPaused(now)
        val codeAllowed = shownOnFrame && screenUsable && !blocked
        // A session without a code, after a lockout or created while nobody could see it, is replaced by a complete
        // one as soon as a code may be shown.
        if (needed && (secret == null || (code == null && codeAllowed))) {
            if (secret != null) expire()
            secret = newSecret()
            failures = 0
            code = if (codeAllowed) (0 until 6).map { random.nextInt(10) }.joinToString("") else null
            expiresAt = now + VALIDITY.toMillis()
        }
        val url = if (needed) secret?.let { s -> adminUrl()?.let { "$it#pair=$s" } } else null
        _display.value =
            PairingDisplay(
                code = if (needed && !blocked) code else null,
                url = url,
                codeLocked = needed && blocked,
                requested = requestedUntil != null,
            )
    }

    private fun expireIfDue(now: Long) {
        if (secret != null && now >= expiresAt) expire()
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

    private fun invalidError() = HttpException(401, "invalid_code", "The code is wrong.")

    private fun expiredError() = HttpException(401, "expired_code", "The code has expired. Have the frame show a new one.")

    // The web UI's pairing dialog reads the seconds from this text.
    private fun lockedError(now: Long): HttpException {
        val seconds = secondsUntil(now, lockedUntil)
        return HttpException(
            429,
            "locked",
            "Too many failed attempts. Try again in $seconds s.",
            headers = mapOf("Retry-After" to seconds.toString()),
        )
    }

    private fun pausedError(now: Long): HttpException {
        val seconds = secondsUntil(now, wrongCodes.peekFirst() + BUDGET_WINDOW.toMillis())
        return HttpException(
            429,
            "code_paused",
            "Too many wrong codes within 24 hours. Pair by QR code, or try again in $seconds s.",
            headers = mapOf("Retry-After" to seconds.toString()),
        )
    }

    private fun secondsUntil(
        now: Long,
        end: Long,
    ) = ((end - now + 999) / 1000).coerceAtLeast(1)

    private fun equal(
        a: String,
        b: String,
    ) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object {
        val VALIDITY: Duration = Duration.ofMinutes(5)
        val REQUEST_WINDOW: Duration = Duration.ofMinutes(5)
        val LOCK: Duration = Duration.ofSeconds(60)
        val MAX_LOCK: Duration = Duration.ofHours(1)
        val BUDGET_WINDOW: Duration = Duration.ofHours(24)
        const val MAX_FAILURES = 5
        const val DAILY_BUDGET = 20
        const val SECRET_BYTES = 16
        private const val MAX_DOUBLINGS = 6
        private const val NEVER = Long.MIN_VALUE
        private const val TAG = "pairing"
    }
}
