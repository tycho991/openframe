package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.sources.Connection
import io.github.tycho991.openframe.core.sources.FakeRemoteFolder
import io.github.tycho991.openframe.core.sources.RemoteFolder
import io.github.tycho991.openframe.core.sources.SourceException
import java.util.concurrent.ConcurrentHashMap

/** Clients that hand out the fakes: one folder for every account, and shares by their token. */
class FakeClients(
    val remote: FakeRemoteFolder = FakeRemoteFolder(),
    val login: LoginFlow = FakeLoginFlow(),
) : NextcloudClients {
    /** A share on the fake server. */
    class FakeShare(
        val remote: FakeRemoteFolder = FakeRemoteFolder(),
        @Volatile var password: String = "",
        @Volatile var isFolder: Boolean = true,
        /** A "file drop" that only accepts uploads. */
        @Volatile var uploadOnly: Boolean = false,
        /** The name of the shared folder. */
        val name: String = "Shared",
    )

    val shares = ConcurrentHashMap<String, FakeShare>()

    /** Thrown when a share is read for adding it, e.g. a server that does not answer. */
    @Volatile var shareFailure: SourceException? = null

    fun share(
        token: String,
        password: String = "",
        isFolder: Boolean = true,
        uploadOnly: Boolean = false,
        name: String = "Shared",
    ) = FakeShare(password = password, isFolder = isFolder, uploadOnly = uploadOnly, name = name).also { shares[token] = it }

    override fun loginFlow(): LoginFlow = login

    /** The folder of every account folder asked for, in order (tests-quality-8: an account syncs its folder, not its root). */
    val accountFolders = java.util.concurrent.CopyOnWriteArrayList<String>()

    override fun accountFolder(
        account: Connection.Account,
        folder: String,
    ): RemoteFolder {
        accountFolders += folder
        return remote
    }

    override fun shareFolder(
        share: Connection.Share,
        folder: String,
    ): RemoteFolder =
        shares[share.token]?.remote
            ?: FakeRemoteFolder().also { it.listFailure = SourceException(SourceException.Kind.NOT_FOUND, "The share was not found") }

    override fun shareInfo(share: Connection.Share): ShareInfo {
        shareFailure?.let { throw it }
        val known = shares[share.token] ?: throw SourceException(SourceException.Kind.NOT_FOUND, "The share was not found")
        if (known.password !=
            share.password
        ) {
            throw SourceException(SourceException.Kind.UNAUTHORIZED, "The share refused the password (HTTP 401)")
        }
        if (known.uploadOnly) throw SourceException(SourceException.Kind.NOT_READABLE, "The share only accepts uploads")
        return ShareInfo(if (known.isFolder) ShareRoot.FOLDER else ShareRoot.FILE, known.name)
    }
}

/**
 * A login flow that the test approves by hand: every flow at once with [approved] as [userId], or one flow with
 * [approve] as any user (design decision 15 of `paired-device-guards`), keyed by its poll token, which is new per start.
 */
class FakeLoginFlow(
    var serverUrl: String = "https://cloud.example.org",
    val loginName: String = "Anna",
    @Volatile var appPassword: String = "app-pw-1234",
    @Volatile var userId: String = "anna",
) : LoginFlow {
    @Volatile var approved = false

    @Volatile var startFailure: SourceException? = null

    /** Thrown when the user ID is asked for after the approval. */
    @Volatile var userIdFailure: SourceException? = null

    @Volatile var revoked = 0

    /** How long an approved poll takes, like a slow server that also has to name the user. */
    @Volatile var donePollDelayMs = 0L

    /** The server an approved poll reports, by default the one the flow started at. */
    @Volatile var reportedServer: String? = null

    /** How long a revocation takes, like a server that does not answer. */
    @Volatile var revokeDelayMs = 0L

    val started = ArrayList<String>()

    /** The app passwords revoked, in order. */
    val revokedPasswords = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** The flow of the last approved poll. */
    @Volatile var poll: LoginStart? = null

    /** Every flow started, in order. */
    val flows = java.util.concurrent.CopyOnWriteArrayList<LoginStart>()

    /** The user each flow was approved as, by its poll token. */
    private val approvals = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The user each app password handed out belongs to. */
    private val users = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The user approves [flow], by default the last one started, as the Nextcloud user [user]. */
    fun approve(
        user: String = userId,
        flow: LoginStart = flows.last(),
    ) {
        approvals[flow.pollToken] = user
    }

    override fun start(serverUrl: String): LoginStart {
        startFailure?.let { throw it }
        started += serverUrl
        val token = "token-${flows.size + 1}"
        return LoginStart(serverUrl, "$serverUrl/index.php/login/v2/flow/abc", "$serverUrl/index.php/login/v2/poll", token).also {
            flows += it
        }
    }

    override fun poll(start: LoginStart): LoginPoll {
        val user = approvals[start.pollToken] ?: (if (approved) userId else return LoginPoll.Pending)
        if (donePollDelayMs > 0) Thread.sleep(donePollDelayMs)
        poll = start
        // A flow approved by itself gets an app password of its own, so that its user can be told apart.
        val password = if (approvals.containsKey(start.pollToken)) "$appPassword-${start.pollToken}" else appPassword
        users[password] = user
        return LoginPoll.Done(reportedServer ?: start.serverUrl, loginName, password)
    }

    override fun userId(
        serverUrl: String,
        loginName: String,
        appPassword: String,
    ): String {
        userIdFailure?.let { throw it }
        return users[appPassword] ?: userId
    }

    override fun revoke(
        serverUrl: String,
        loginName: String,
        appPassword: String,
    ) {
        if (revokeDelayMs > 0) Thread.sleep(revokeDelayMs)
        revokedPasswords += appPassword
        revoked++
    }
}
