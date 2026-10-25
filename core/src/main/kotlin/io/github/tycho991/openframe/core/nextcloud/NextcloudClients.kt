package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.sources.Connection
import io.github.tycho991.openframe.core.sources.RemoteFolder

/** Where the Nextcloud kinds get their connections from; the tests hand them fakes. */
interface NextcloudClients {
    fun loginFlow(): LoginFlow

    /** The folder [folder] of a connected account. */
    fun accountFolder(
        account: Connection.Account,
        folder: String,
    ): RemoteFolder

    /** The folder [folder] below the top of a share. */
    fun shareFolder(
        share: Connection.Share,
        folder: String,
    ): RemoteFolder

    /** Reads the top of a share once, before it is stored. Throws [io.github.tycho991.openframe.core.sources.SourceException]. */
    fun shareInfo(share: Connection.Share): ShareInfo
}

class OkHttpNextcloudClients(
    httpFactory: () -> NextcloudHttp = { NextcloudHttp() },
) : NextcloudClients {
    /** Created at the first use: reading the trust anchors and building the TLS context is not needed on a frame without Nextcloud. */
    private val http by lazy(httpFactory)

    override fun loginFlow(): LoginFlow = OkHttpLoginFlow(http)

    override fun accountFolder(
        account: Connection.Account,
        folder: String,
    ): RemoteFolder = WebDavFolder(http, account.serverUrl, account.userId, account.loginName, account.appPassword, folder)

    override fun shareFolder(
        share: Connection.Share,
        folder: String,
    ): RemoteFolder = ShareFolder(http, share.serverUrl, share.token, share.password, folder)

    override fun shareInfo(share: Connection.Share): ShareInfo =
        ShareFolder(http, share.serverUrl, share.token, share.password, "/").probe()
}
