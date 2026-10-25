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
import java.net.URI

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
 * every photo ID `nextcloud-<sha1>` and with it the hidden state stays as it was. `nextcloud.json` stays where it is,
 * so that going back to the older build still works, until that source is removed.
 */
class NextcloudMigration(
    stateDir: File,
    cacheDir: File,
    private val configs: SourceConfigStore,
    private val results: SyncResultStore,
    private val log: AppLog,
) {
    private val oldConfig = File(stateDir, "nextcloud.json")
    private val oldStatus = File(stateDir, "nextcloud-status.json")
    private val oldOriginals = File(cacheDir, "nextcloud")
    private val newOriginals = File(cacheDir, "sources/$ID")

    /** Runs when there is no `sources.json` yet. `sources.json` is written last, so a migration cut off halfway runs again. */
    suspend fun migrate() {
        val old = read(oldConfig, StoredNextcloud.serializer()) ?: return
        if (!old.connected) return
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
                name = URI(old.serverUrl).host ?: old.serverUrl,
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
    }

    /** After a source was removed: the files of 0.1.0 go with the source they became. */
    fun sourceRemoved(source: StoredSource) {
        if (source.id != ID) return
        oldConfig.delete()
        oldStatus.delete()
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
