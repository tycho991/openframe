package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.sources.Connection
import io.github.tycho991.openframe.core.sources.SourceConfigStore
import io.github.tycho991.openframe.core.sources.StoredSource
import io.github.tycho991.openframe.core.sources.StoredSyncResult
import io.github.tycho991.openframe.core.sources.SyncResultStore
import io.github.tycho991.openframe.core.store.StoreJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.io.File

/** What `nextcloud.json` of 0.1.0 holds. Read for the migration only; [toString] hides the password. */
@Serializable
data class StoredNextcloud(
    val serverUrl: String = "",
    val userId: String = "",
    val loginName: String = "",
    val appPassword: String = "",
    val enabled: Boolean = true,
    val folder: String = "",
    val includeSubfolders: Boolean = true,
    val removeVanished: Boolean = true,
    val intervalMinutes: Int = StoredSource.DEFAULT_INTERVAL_MINUTES,
    val originalsBudgetMb: Int = StoredSource.DEFAULT_BUDGET_MB,
) {
    val connected: Boolean get() = serverUrl.isNotEmpty() && appPassword.isNotEmpty()

    override fun toString() = "StoredNextcloud(server=$serverUrl, user=$loginName, connected=$connected, folder=$folder)"
}

/**
 * The one Nextcloud connection of 0.1.0 becomes a source of kind `nextcloud-account` with the ID `nextcloud`, so that
 * every photo ID `nextcloud-<sha1>` and with it the hidden state stays as it was. The files of 0.1.0 are deleted at the
 * next start with a readable list of sources ([cleanUpLeftovers]), and an app password in them that the source does not
 * use is revoked on its server (spec `nextcloud-source`, "No orphaned app passwords"). There is no way back to 0.1.0.
 */
class NextcloudMigration(
    stateDir: File,
    cacheDir: File,
    private val configs: SourceConfigStore,
    private val results: SyncResultStore,
    private val log: AppLog,
    /** Asks the server of 0.1.0 to forget an app password, in the background; failures are logged there. */
    private val revokeLater: (StoredNextcloud) -> Unit = {},
) {
    private val oldConfig = File(stateDir, "nextcloud.json")
    private val oldStatus = File(stateDir, "nextcloud-status.json")
    private val oldOriginals = File(cacheDir, "nextcloud")
    private val newOriginals = File(cacheDir, "sources/$ID")

    /**
     * Runs when there is no `sources.json` yet. `sources.json` is written last, so a migration cut off halfway runs again.
     * `true` if it wrote `sources.json`.
     */
    suspend fun migrate(): Boolean {
        val old = read(oldConfig, StoredNextcloud.serializer()) ?: return false
        if (!old.connected) return false
        read(oldStatus, StoredSyncResult.serializer())?.let { results.put(ID, it) }
        if (oldOriginals.isDirectory && !newOriginals.exists()) {
            newOriginals.parentFile?.mkdirs()
            // A failed rename only costs downloading the originals again.
            if (!oldOriginals.renameTo(newOriginals)) log.w(TAG, "The downloaded originals could not be moved; they are fetched again")
        }
        configs.add(
            StoredSource(
                id = ID,
                kind = NextcloudAccountKind.ID,
                name = SourceConfigStore.hostOf(old.serverUrl),
                connection = Connection.Account(old.serverUrl, old.userId, old.loginName, old.appPassword),
                enabled = old.enabled,
                folder = old.folder,
                includeSubfolders = old.includeSubfolders,
                removeVanished = old.removeVanished,
                intervalMinutes = old.intervalMinutes,
                originalsBudgetMb = old.originalsBudgetMb,
            ),
        )
        results.flush()
        configs.flush()
        log.i(TAG, "The Nextcloud connection of an older version is now the source $ID")
        return true
    }

    /**
     * Deletes the files of 0.1.0. Only after `sources.json` was read or written by [migrate], and only when the sources
     * run (not in safe mode): with a list that cannot be read the source `nextcloud` would look gone while its password
     * is in use. The password of 0.1.0 is revoked if it is not the one the source `nextcloud` uses, or that source is gone.
     */
    fun cleanUpLeftovers() {
        if (!oldConfig.exists() && !oldStatus.exists()) return
        val current = (configs.get(ID)?.connection as? Connection.Account)?.appPassword
        revokeIfUnused(current)
        oldConfig.delete()
        oldStatus.delete()
        log.i(TAG, "The files of the Nextcloud connection of version 0.1.0 were deleted")
    }

    /** After a source was removed: the files of 0.1.0 go with the source they became, and a password only they held. */
    fun sourceRemoved(source: StoredSource) {
        if (source.id != ID) return
        if (oldConfig.exists()) revokeIfUnused((source.connection as? Connection.Account)?.appPassword)
        oldConfig.delete()
        oldStatus.delete()
    }

    private fun revokeIfUnused(used: String?) {
        val old = read(oldConfig, StoredNextcloud.serializer()) ?: return
        if (old.connected && old.appPassword != used) revokeLater(old)
    }

    private fun <T> read(
        file: File,
        serializer: KSerializer<T>,
    ): T? {
        if (!file.isFile) return null
        return try {
            StoreJson.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            log.w(TAG, "${file.name} of an older version is not readable and is not migrated")
            null
        }
    }

    companion object {
        /** The source ID of 0.1.0, the source part of the IDs of its photos. */
        const val ID = "nextcloud"
        private const val TAG = "sources"
    }
}
