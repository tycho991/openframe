package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.ValidationException
import io.github.tycho991.openframe.core.http.HttpException
import io.github.tycho991.openframe.core.platform.Clock
import io.github.tycho991.openframe.core.sources.Connection
import io.github.tycho991.openframe.core.sources.SourceConfigStore
import io.github.tycho991.openframe.core.sources.SourceErrorCodes
import io.github.tycho991.openframe.core.sources.SourceException
import io.github.tycho991.openframe.core.sources.SourceRegistry
import io.github.tycho991.openframe.core.sources.StoredSource
import java.security.SecureRandom
import java.time.Instant

/** What a poll of the login flow says. */
data class LoginState(
    val state: String,
    val user: String? = null,
    val sourceId: String? = null,
    val error: String? = null,
    /** For `failed`: a value of the contract's `SourceErrorCode`. */
    val code: String? = null,
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
    /** Whether a client is still paired; the flow of one that is not ends (design decision 15 of `paired-device-guards`). */
    private val paired: (String) -> Boolean = { true },
    private val random: SecureRandom = SecureRandom(),
) {
    private class Flow(
        val id: String,
        val start: LoginStart,
        val startedAtMs: Long,
        /** The account source a confirmation login is for, `null` for a login that adds or renews a source. */
        val confirmSourceId: String?,
    )

    /** At most one flow per paired device, by client ID; only that device sees it. */
    private val flows = HashMap<String, Flow>()

    private val configs get() = sources.configs

    private val grants get() = sources.grants

    /**
     * Starts the login flow at [serverUrl] for [clientId], replacing only that client's own flow. With [confirmSourceId]
     * it is a confirmation login for that account source, on its stored server address, and [serverUrl] is ignored
     * (design decision 16 of `paired-device-guards`): 404 for an unknown ID, 400 for a source that is no account; a full
     * frame does not refuse it, since nothing is added. Otherwise a 400 for an address that is none and a 409 if the frame
     * is full and has no account the login could renew. A 502 if the server cannot be asked.
     */
    @Synchronized
    fun startLogin(
        serverUrl: String?,
        clientId: String,
        confirmSourceId: String? = null,
    ): StartedLogin {
        sweep()
        val url =
            if (confirmSourceId != null) {
                confirmedServer(confirmSourceId)
            } else {
                typedServer(serverUrl ?: throw ValidationException(mapOf("serverUrl" to "An address starting with http:// or https://")))
            }
        val start =
            try {
                clients.loginFlow().start(url)
            } catch (e: SourceException) {
                if (e.kind == SourceException.Kind.INSECURE_LOGIN) throw HttpException(502, "nextcloud_insecure_login", e.message.orEmpty())
                throw HttpException(502, "nextcloud_unreachable", e.message.orEmpty())
            }
        val started = Flow(randomId(), start, clock.millis(), confirmSourceId)
        flows[clientId] = started
        return StartedLogin(started.id, start.loginUrl, Instant.ofEpochMilli(started.startedAtMs + LOGIN_VALIDITY_MS))
    }

    /** The server of the account source [id] for a confirmation login. */
    private fun confirmedServer(id: String): String {
        val source = configs.get(id) ?: throw HttpException.notFound("No such source")
        val account = source.connection as? Connection.Account
        if (account == null || sources.kindOf(source)?.needsFolder != true) {
            throw ValidationException(mapOf("confirmSourceId" to "Only an account source is confirmed by a login"))
        }
        return account.serverUrl
    }

    /** The server for a login that adds or renews a source. */
    private fun typedServer(serverUrl: String): String {
        val typed = SourceConfigStore.normalizeServerUrl(serverUrl)
        val all = configs.sources.value
        if (all.size >= StoredSource.MAX_SOURCES && all.none { it.connection is Connection.Account }) throw SourceConfigStore.tooMany()
        // An account that is on https already stays there, also when its address is typed with http: the whole flow,
        // the app password included, runs over https from its first request.
        return all
            .map { it.connection }
            .filterIsInstance<Connection.Account>()
            .firstOrNull {
                it.serverUrl.startsWith("https://") && SourceConfigStore.serverKey(it.serverUrl) == SourceConfigStore.serverKey(typed)
            }?.serverUrl ?: typed
    }

    /** Ends the flows that expired and those of clients that are no longer paired, so a revoked device's flow ends. */
    private fun sweep() {
        val now = clock.millis()
        flows.entries.removeAll { (client, flow) -> now - flow.startedAtMs >= LOGIN_VALIDITY_MS || !paired(client) }
    }

    /**
     * Asks the server once whether the user has approved the flow [flowId] of [clientId]; a flow of another client counts
     * as unknown (`expired`) and stays as it is. On approval the account becomes a source, or a source that is the same
     * server and user gets the new credentials and keeps its photos and settings; either way [clientId] may widen its
     * folder for a while. A confirmation login only checks the account and revokes its new app password. An app password
     * the frame cannot use after the approval is revoked again in the background (spec "No orphaned app passwords").
     */
    @Synchronized
    fun pollLogin(
        flowId: String,
        clientId: String,
    ): LoginState {
        sweep()
        val current = flows[clientId]
        if (current == null || current.id != flowId) return LoginState("expired")
        val login = clients.loginFlow()
        val polled =
            try {
                login.poll(current.start)
            } catch (e: SourceException) {
                flows.remove(clientId)
                return LoginState("failed", error = e.message, code = e.kind.code)
            }
        if (polled == LoginPoll.Pending) return LoginState("pending")
        val done = polled as LoginPoll.Done
        flows.remove(clientId)
        return try {
            val userId = login.userId(done.serverUrl, done.loginName, done.appPassword)
            val account = Connection.Account(done.serverUrl, userId, done.loginName, done.appPassword)
            if (current.confirmSourceId != null) return confirm(current, done, account, clientId)
            val all = configs.sources.value
            // By the address the server reports, else by the one the login started with: a server that moved to https on
            // another port is still the same source.
            val known =
                all.firstOrNull { sameAccount(it.connection, account) }
                    ?: all.firstOrNull { sameAccount(it.connection, account.copy(serverUrl = current.start.serverUrl)) }
            if (known != null) {
                sources.reconnect(known.id, account)
                grants.grant(clientId, known.id)
                LoginState("done", user = done.loginName, sourceId = known.id)
            } else if (all.size >= StoredSource.MAX_SOURCES) {
                // The server issued an app password the frame cannot use: it must not stay active there.
                revokeNew(done)
                LoginState(
                    "failed",
                    error = "The frame has ${StoredSource.MAX_SOURCES} sources already; remove one first",
                    code = SourceErrorCodes.TOO_MANY_SOURCES,
                )
            } else {
                val added =
                    sources.add(
                        StoredSource(configs.newId(), NextcloudAccountKind.ID, SourceConfigStore.hostOf(done.serverUrl), account),
                    )
                grants.grant(clientId, added.id)
                LoginState("done", user = done.loginName, sourceId = added.id)
            }
        } catch (e: SourceException) {
            revokeNew(done)
            LoginState("failed", error = e.message, code = e.kind.code)
        } catch (e: HttpException) {
            revokeNew(done)
            val code = if (e.code == "too_many_sources") SourceErrorCodes.TOO_MANY_SOURCES else SourceErrorCodes.UNEXPECTED
            LoginState("failed", error = e.message, code = code)
        }
    }

    /**
     * A confirmation login was approved as [account] (design decision 16): compared with the connection of the source it
     * confirms only, by the reported address and then by the start address. On a match [clientId] may widen that
     * source's folder; any other account, also that of another source, fails with `other_account`. Either way the new
     * app password is revoked, and no source is added or reconnected: the source keeps its password, status and result.
     */
    private fun confirm(
        flow: Flow,
        done: LoginPoll.Done,
        account: Connection.Account,
        clientId: String,
    ): LoginState {
        revokeNew(done)
        val id = flow.confirmSourceId!!
        val stored = configs.get(id)?.connection as? Connection.Account
        val same =
            stored != null &&
                (sameAccount(stored, account) || sameAccount(stored, account.copy(serverUrl = flow.start.serverUrl)))
        if (!same) {
            return LoginState(
                "failed",
                error = "The login was approved by another account than the one of this source",
                code = SourceErrorCodes.OTHER_ACCOUNT,
            )
        }
        grants.grant(clientId, id)
        return LoginState("done", user = done.loginName, sourceId = id)
    }

    /** The app password of [done] is not used: revoked in the background, as a silent server would hold the request up. */
    private fun revokeNew(done: LoginPoll.Done) {
        val login = clients.loginFlow()
        sources.revokeLater("The unused app password for ${SourceConfigStore.hostOf(done.serverUrl)}") {
            login.revoke(done.serverUrl, done.loginName, done.appPassword)
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

                    SourceException.Kind.NOT_FOUND, SourceException.Kind.GONE -> {
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
        val shownName = normalizedName ?: SourceConfigStore.normalizeName(info.name) ?: SourceConfigStore.hostOf(share.serverUrl)
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

    private fun randomId() = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val LOGIN_VALIDITY_MS = 20 * 60_000L
    }
}
