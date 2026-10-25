package io.github.tycho991.openframe.core.sources

import io.github.tycho991.openframe.core.store.JsonStore
import kotlinx.serialization.Serializable

/** The result of the last sync of a source as it is stored, so that it survives a restart. */
@Serializable
data class StoredSyncResult(
    val lastSyncAtMs: Long? = null,
    val lastSyncOk: Boolean? = null,
    val error: String? = null,
    val added: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
)

/** The content of `sources-status.json`: the last result of each source by its ID. */
@Serializable
data class StoredSyncResults(
    val results: Map<String, StoredSyncResult> = emptyMap(),
)

/** What the API reports for a source: the stored result and whether a sync is running or waiting at the moment. */
data class SourceStatus(
    /** A sync of this source runs, or is asked for and about to start. */
    val syncing: Boolean,
    val last: StoredSyncResult,
    /** A sync of this source is due but waits until the sync of another source ends. */
    val waiting: Boolean = false,
)

/** The last results of all sources in one file. */
class SyncResultStore(
    private val store: JsonStore<StoredSyncResults>,
) {
    private var results: Map<String, StoredSyncResult> = emptyMap()

    @Synchronized
    fun load() {
        results = store.load()?.results.orEmpty()
    }

    @Synchronized
    fun get(id: String): StoredSyncResult = results[id] ?: StoredSyncResult()

    @Synchronized
    fun put(
        id: String,
        result: StoredSyncResult,
    ) {
        results = results + (id to result)
        store.save(StoredSyncResults(results))
    }

    @Synchronized
    fun remove(id: String) {
        if (id !in results) return
        results = results - id
        store.save(StoredSyncResults(results))
    }

    suspend fun flush() = store.flush()
}
