package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.ValidationException
import io.github.tycho991.openframe.core.http.HttpException
import io.github.tycho991.openframe.core.platform.Clock
import io.github.tycho991.openframe.core.sources.Connection
import io.github.tycho991.openframe.core.sources.SourceConfigStore
import io.github.tycho991.openframe.core.sources.SourceException
import io.github.tycho991.openframe.core.sources.SourceRegistry
import io.github.tycho991.openframe.core.sources.StoredSource
import java.net.URI
import java.security.SecureRandom
import java.time.Instant

/** What a poll of the login flow says. */
data class LoginState(
    val state: String,
    val user: String? = null,
    val sourceId: String? = null,
    val error: String? = null,
)

data class StartedLogin(
    val flowId: String,
    val loginUrl: String,
    val expiresAt: Instant,
)

/**
 * How Nextcloud sources come to the frame: an account through the login flow, a share through its link. The routes
 * only translate; the rules are here. What a source does once it exists is the [SourceRegistry]'s.
 */
class NextcloudConnector(
    private val sources: SourceRegistry,
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

    private val configs get() = sources.configs

    /**
     * Starts the login flow at [serverUrl]. Throws a 400 for an address that is none, a 409 if the frame is full and has no
     * account the login could renew, and a 502 if the server cannot be asked.
     */
    @Synchronized
    fun startLogin(serverUrl: String): StartedLogin {
        val url = SourceConfigStore.normalizeServerUrl(serverUrl)
        val all = configs.sources.value
        if (all.size >= StoredSource.MAX_SOURCES && all.none { it.connection is Connection.Account }) throw SourceConfigStore.tooMany()
        val start =
            try {
                clients.loginFlow().start(url)
            } catch (e: SourceException) {
                throw HttpException(502, "nextcloud_unreachable", e.message.orEmpty())
            }
        val started = Flow(randomId(), start, clock.millis())
        flow = started
        return StartedLogin(started.id, start.loginUrl, Instant.ofEpochMilli(started.startedAtMs + LOGIN_VALIDITY_MS))
    }

    /**
     * Asks the server once whether the user has approved. On approval the account becomes a source, or a source that is the
     * same server and user gets the new credentials and keeps its photos and settings.
     */
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
                    flow = null
                    val userId = login.userId(polled.serverUrl, polled.loginName, polled.appPassword)
                    val account = Connection.Account(polled.serverUrl, userId, polled.loginName, polled.appPassword)
                    val known = configs.sources.value.firstOrNull { sameAccount(it.connection, account) }
                    if (known != null) {
                        sources.reconnect(known.id, account)
                        LoginState("done", user = polled.loginName, sourceId = known.id)
                    } else if (configs.sources.value.size >= StoredSource.MAX_SOURCES) {
                        LoginState("failed", error = "The frame has ${StoredSource.MAX_SOURCES} sources already; remove one first")
                    } else {
                        val added =
                            sources.add(
                                StoredSource(configs.newId(), NextcloudAccountKind.ID, hostOf(polled.serverUrl), account),
                            )
                        LoginState("done", user = polled.loginName, sourceId = added.id)
                    }
                }
            }
        } catch (e: SourceException) {
            flow = null
            LoginState("failed", error = e.message)
        }
    }

    /**
     * Adds the share at [link] after reading it once. Nothing is stored on a failure: a 400 at `link` for a link that is
     * no share, a share that does not exist, one of a single file or one that only accepts uploads, at `password` for a
     * missing or wrong password; a 409
     * for a share that is a source already or a full frame; a 502 for a server that does not answer.
     */
    @Synchronized
    fun addShare(
        link: String,
        password: String?,
        name: String?,
    ): StoredSource {
        val parsed = ShareLink.parse(link)
        val errors = LinkedHashMap<String, String>()
        if (parsed == null) errors["link"] = "The link of a share, such as https://cloud.example.org/s/AbC123"
        val normalizedName = name?.let { SourceConfigStore.normalizeName(it) }
        if (name != null && normalizedName == null) errors["name"] = "1 to ${StoredSource.NAME_MAX_LENGTH} characters"
        if (errors.isNotEmpty()) throw ValidationException(errors)
        val share = Connection.Share(parsed!!.serverUrl, parsed.token, password.orEmpty())
        if (configs.sources.value.any { sameShare(it.connection, share) }) {
            throw HttpException(409, "source_exists", "This share is a source already")
        }
        if (configs.sources.value.size >= StoredSource.MAX_SOURCES) throw SourceConfigStore.tooMany()
        val info =
            try {
                clients.shareInfo(share)
            } catch (e: SourceException) {
                throw when (e.kind) {
                    SourceException.Kind.UNAUTHORIZED -> {
                        if (share.password.isEmpty()) {
                            HttpException(
                                400,
                                "share_password_required",
                                "The share is protected by a password",
                                mapOf("password" to "Required"),
                            )
                        } else {
                            HttpException(
                                400,
                                "share_password_wrong",
                                "The password of the share is wrong",
                                mapOf("password" to "Wrong password"),
                            )
                        }
                    }

                    SourceException.Kind.NOT_FOUND -> {
                        HttpException(400, "share_not_found", "The share was not found", mapOf("link" to "No such share"))
                    }

                    SourceException.Kind.NOT_READABLE -> {
                        HttpException(400, "share_not_readable", "The share only accepts uploads", mapOf("link" to "Upload only"))
                    }

                    else -> {
                        HttpException(502, "nextcloud_unreachable", e.message.orEmpty())
                    }
                }
            }
        if (info.root != ShareRoot.FOLDER) {
            throw HttpException(400, "share_not_a_folder", "The link is the share of a single file", mapOf("link" to "Not a folder"))
        }
        // Without a name of its own a share is called after the shared folder, so that several shares can be told apart.
        val shownName = normalizedName ?: SourceConfigStore.normalizeName(info.name) ?: hostOf(share.serverUrl)
        return sources.add(StoredSource(configs.newId(), NextcloudShareKind.ID, shownName, share, folder = "/"))
    }

    private fun sameAccount(
        stored: Connection,
        account: Connection.Account,
    ) = stored is Connection.Account &&
        stored.userId == account.userId &&
        SourceConfigStore.serverKey(stored.serverUrl) == SourceConfigStore.serverKey(account.serverUrl)

    private fun sameShare(
        stored: Connection,
        share: Connection.Share,
    ) = stored is Connection.Share &&
        stored.token == share.token &&
        SourceConfigStore.serverKey(stored.serverUrl) == SourceConfigStore.serverKey(share.serverUrl)

    private fun hostOf(serverUrl: String) = URI(serverUrl).host ?: serverUrl

    private fun randomId() = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val LOGIN_VALIDITY_MS = 20 * 60_000L
    }
}
