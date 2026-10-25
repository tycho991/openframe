package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.sources.Connection
import io.github.tycho991.openframe.core.sources.RemoteFolder
import io.github.tycho991.openframe.core.sources.SourceKind
import io.github.tycho991.openframe.core.sources.StoredSource

/** A Nextcloud account connected with the login flow: the user chooses a folder, removing it revokes the app password. */
class NextcloudAccountKind(
    private val clients: NextcloudClients,
) : SourceKind {
    override val id = ID
    override val needsFolder = true

    override fun remoteFolder(source: StoredSource): RemoteFolder =
        clients.accountFolder(source.connection as Connection.Account, source.folder)

    override fun revoke(source: StoredSource) {
        val account = source.connection as Connection.Account
        clients.loginFlow().revoke(account.serverUrl, account.loginName, account.appPassword)
    }

    companion object {
        const val ID = "nextcloud-account"
    }
}

/** The link of a Nextcloud folder share: no account, the folder is the shared one, nothing to revoke. */
class NextcloudShareKind(
    private val clients: NextcloudClients,
) : SourceKind {
    override val id = ID
    override val needsFolder = false

    override fun remoteFolder(source: StoredSource): RemoteFolder =
        clients.shareFolder(source.connection as Connection.Share, source.folder)

    companion object {
        const val ID = "nextcloud-share"
    }
}
