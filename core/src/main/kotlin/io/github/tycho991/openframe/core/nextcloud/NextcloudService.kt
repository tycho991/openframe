package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.http.HttpException
import io.github.tycho991.openframe.core.platform.Clock
import kotlinx.serialization.json.JsonObject
import java.security.SecureRandom
import java.time.Instant

/** What a poll of the login flow says. */
data class LoginState(
    val state: String,
    val user: String? = null,
    val error: String? = null,
)

data class StartedLogin(
    val flowId: String,
    val loginUrl: String,
    val expiresAt: Instant,
)

/**
 * What the API does with the Nextcloud source: settings, the login, a sync on request and disconnecting. The routes
 * only translate; the rules are here.
 */
class NextcloudService(
    val configs: NextcloudConfigStore,
    private val source: NextcloudSource,
    private val scheduler: NextcloudScheduler,
    private val clients: NextcloudClients,
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
) {
    private class Flow(
        val id: String,
        val start: LoginStart,
        val startedAtMs: Long,
    )

    private var flow: Flow? = null

    val status get() = scheduler.status

    /** Validates and applies a partial change, then lets the source sync again soon. */
    fun patch(patch: JsonObject): NextcloudConfig {
        val view = configs.patch(patch)
        scheduler.configChanged()
        return view
    }

    /** Starts the login flow at [serverUrl]. Throws a 400 for an address that is none and a 502 if the server cannot be asked. */
    @Synchronized
    fun startLogin(serverUrl: String): StartedLogin {
        val url = NextcloudConfigStore.normalizeServerUrl(serverUrl)
        val start =
            try {
                clients.loginFlow().start(url)
            } catch (e: NextcloudException) {
                throw HttpException(502, "nextcloud_unreachable", e.message.orEmpty())
            }
        val started = Flow(randomId(), start, clock.millis())
        flow = started
        return StartedLogin(started.id, start.loginUrl, Instant.ofEpochMilli(started.startedAtMs + LOGIN_VALIDITY_MS))
    }

    /** Asks the server once whether the user has approved; on approval stores the credentials. */
    @Synchronized
    fun pollLogin(flowId: String): LoginState {
        val current = flow
        if (current == null || current.id != flowId || clock.millis() - current.startedAtMs >= LOGIN_VALIDITY_MS) {
            if (current?.id == flowId) flow = null
            return LoginState("expired")
        }
        val login = clients.loginFlow()
        return try {
            when (val polled = login.poll(current.start)) {
                LoginPoll.Pending -> {
                    LoginState("pending")
                }

                is LoginPoll.Done -> {
                    val userId = login.userId(polled.serverUrl, polled.loginName, polled.appPassword)
                    val before = configs.config.value
                    // Another account or server: the photos of the old one do not belong to it.
                    if (before.connected && (before.serverUrl != polled.serverUrl || before.userId != userId)) source.clearPhotos()
                    configs.connect(polled.serverUrl, userId, polled.loginName, polled.appPassword)
                    flow = null
                    scheduler.reset()
                    scheduler.configChanged()
                    LoginState("done", user = polled.loginName)
                }
            }
        } catch (e: NextcloudException) {
            flow = null
            LoginState("failed", error = e.message)
        }
    }

    /** `false` if a sync is already running. Throws a 409 if there is no account. */
    fun requestSync(): Boolean {
        if (!configs.config.value.connected) throw HttpException(409, "nextcloud_not_connected", "The Nextcloud source is not connected")
        return scheduler.requestSync()
    }

    fun disconnect() {
        synchronized(this) { flow = null }
        source.disconnect()
        scheduler.reset()
    }

    private fun randomId() = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val LOGIN_VALIDITY_MS = 20 * 60_000L
    }
}
