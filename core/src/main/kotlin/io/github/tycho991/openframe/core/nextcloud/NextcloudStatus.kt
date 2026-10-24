package io.github.tycho991.openframe.core.nextcloud

import kotlinx.serialization.Serializable

/** The result of the last sync as it is stored (`nextcloud-status.json`), so that it survives a restart. */
@Serializable
data class StoredSyncResult(
    val lastSyncAtMs: Long? = null,
    val lastSyncOk: Boolean? = null,
    val error: String? = null,
    val added: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
)

/** What the API reports: the stored result and whether a sync is running at the moment. */
data class NextcloudStatus(
    val syncing: Boolean,
    val last: StoredSyncResult,
)
