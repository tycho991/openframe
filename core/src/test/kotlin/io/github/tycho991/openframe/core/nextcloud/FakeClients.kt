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

    override fun accountFolder(
        account: Connection.Account,
        folder: String,
    ): RemoteFolder = remote

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

/** A login flow that the test approves by hand. */
class FakeLoginFlow(
    var serverUrl: String = "https://cloud.example.org",
    val loginName: String = "Anna",
    val appPassword: String = "app-pw-1234",
    @Volatile var userId: String = "anna",
) : LoginFlow {
    @Volatile var approved = false

    @Volatile var startFailure: SourceException? = null

    /** Thrown when the user ID is asked for after the approval. */
    @Volatile var userIdFailure: SourceException? = null

    @Volatile var revoked = 0

    /** How long an approved poll takes, like a slow server that also has to name the user. */
    @Volatile var donePollDelayMs = 0L

    val started = ArrayList<String>()

    override fun start(serverUrl: String): LoginStart {
        startFailure?.let { throw it }
        started += serverUrl
        return LoginStart(serverUrl, "$serverUrl/index.php/login/v2/flow/abc", "$serverUrl/index.php/login/v2/poll", "token")
    }

    override fun poll(start: LoginStart): LoginPoll {
        if (!approved) return LoginPoll.Pending
        if (donePollDelayMs > 0) Thread.sleep(donePollDelayMs)
        return LoginPoll.Done(start.serverUrl, loginName, appPassword)
    }

    override fun userId(
        serverUrl: String,
        loginName: String,
        appPassword: String,
    ): String {
        userIdFailure?.let { throw it }
        return userId
    }

    override fun revoke(
        serverUrl: String,
        loginName: String,
        appPassword: String,
    ) {
        revoked++
    }
}
