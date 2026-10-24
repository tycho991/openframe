package io.github.tycho991.openframe.core.nextcloud

/** Where the source gets its connections from; the tests hand it fakes. */
interface NextcloudClients {
    fun loginFlow(): LoginFlow

    /** The configured folder of a connected account. */
    fun remoteFolder(config: StoredNextcloud): RemoteFolder
}

class OkHttpNextcloudClients(
    httpFactory: () -> NextcloudHttp = { NextcloudHttp() },
) : NextcloudClients {
    /** Created at the first use: reading the trust anchors and building the TLS context is not needed on a frame without Nextcloud. */
    private val http by lazy(httpFactory)

    override fun loginFlow(): LoginFlow = OkHttpLoginFlow(http)

    override fun remoteFolder(config: StoredNextcloud): RemoteFolder =
        WebDavFolder(http, config.serverUrl, config.userId, config.loginName, config.appPassword, config.folder)
}
