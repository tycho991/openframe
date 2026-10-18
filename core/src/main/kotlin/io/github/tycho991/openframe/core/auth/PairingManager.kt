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
 * Was der Rahmen zur Kopplung anzeigen soll.
 *
 * [code] ist der gültige Code, solange ihn jemand braucht: der Rahmen selbst
 * (Leerzustand, Hinweis) oder eine Anfrage aus der Web-UI. [requestedUntil]
 * ist gesetzt, solange der Hinweis wegen einer solchen Anfrage stehen bleibt.
 */
data class PairingDisplay(
    val code: String?,
    val requestedUntil: Instant?,
)

/**
 * Kopplungscodes: sechs Ziffern, 5 Minuten gültig, nur einmal verwendbar.
 * Nach 5 Fehlversuchen wird der Code verworfen und die Kopplung 60 Sekunden gesperrt.
 * Läuft ein angezeigter Code ab, ersetzt [tick] ihn durch einen neuen.
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

    /** Der Rahmen zeigt gerade einen Code (Leerzustand oder Hinweis) und braucht einen gültigen. */
    @Synchronized
    fun setShownOnFrame(shown: Boolean) {
        shownOnFrame = shown
        refresh()
    }

    /** `POST /api/v1/pairing/code`: Der Rahmen zeigt 5 Minuten lang einen gültigen Code. */
    @Synchronized
    fun requestDisplay() {
        val now = clock.now()
        if (now.isBefore(lockedUntil)) throw locked(now)
        requestedUntil = now + REQUEST_WINDOW
        refresh()
    }

    /** Einmal pro Sekunde: abgelaufene Codes ersetzen, Anfragefenster schließen. */
    @Synchronized
    fun tick() = refresh()

    /** Prüft [input] und legt bei Erfolg einen Client an. Liefert Token und Client. */
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
            log.i(TAG, "Gekoppelt: ${result.second.name}")
            return result
        }
        if (lastExpiredCode != null && equal(input, lastExpiredCode!!)) {
            throw HttpException(401, "expired_code", "Der Code ist abgelaufen. Am Rahmen einen neuen anzeigen lassen.")
        }
        failures++
        if (failures >= MAX_FAILURES) {
            log.w(TAG, "$MAX_FAILURES falsche Codes, Kopplung für ${LOCK.seconds} s gesperrt")
            failures = 0
            code = null
            lastExpiredCode = null
            lockedUntil = now + LOCK
            refresh()
        }
        throw HttpException(401, "invalid_code", "Der Code ist falsch.")
    }

    /** Verwirft den aktuellen Code, z. B. nach „Alle Kopplungen aufheben“, und zeigt einen neuen. */
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
            "Zu viele Fehlversuche. Erneut in $seconds s.",
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
