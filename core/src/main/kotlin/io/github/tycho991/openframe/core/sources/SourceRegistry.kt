package io.github.tycho991.openframe.core.sources

import io.github.tycho991.openframe.core.http.HttpException
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.PhotoIndexStore
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.Clock
import io.github.tycho991.openframe.core.platform.MediaEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.ConcurrentHashMap

/**
 * All remote sources of the frame, each with its [RemoteSource] and its [SourceScheduler]: what the API, the
 * rendering of originals and the network callback talk to. Adding, changing or removing one source touches only
 * that one; the syncs of all of them take turns on one lock.
 */
class SourceRegistry(
    private val scope: CoroutineScope,
    val configs: SourceConfigStore,
    private val results: SyncResultStore,
    kinds: List<SourceKind>,
    private val index: PhotoIndexStore,
    private val engine: MediaEngine,
    /** `cache/sources`: the originals of each source are in a folder named by its ID. */
    private val originalsDir: File,
    /** A photo left the library: its renderings are not needed any more. */
    private val onPhotoRemoved: (PhotoEntry) -> Unit,
    private val clock: Clock,
    private val log: AppLog,
    /** Called after a source was removed, for files that an older version kept for it. */
    private val onSourceRemoved: (StoredSource) -> Unit = {},
    private val freeBytes: (File) -> Long = { it.usableSpace },
) {
    private class Running(
        val source: RemoteSource,
        val scheduler: SourceScheduler,
    )

    private val kinds = kinds.associateBy { it.id }
    private val running = ConcurrentHashMap<String, Running>()
    private val syncLock = Mutex()

    /** Starts the schedules of the stored sources. */
    fun start() {
        for (config in configs.sources.value) launch(config)
    }

    fun kindOf(source: StoredSource): SourceKind? = kinds[source.kind]

    /** The source with [id] for a rendering, `null` for an unknown one. */
    fun source(id: String): RemoteSource? = running[id]?.source

    fun status(id: String): SourceStatus = running[id]?.scheduler?.status?.value ?: SourceStatus(false, results.get(id))

    /** Stores and starts [source]. One that can sync at once does, so that its first sync shows as running. */
    fun add(source: StoredSource): StoredSource {
        val added = configs.add(source)
        val started = launch(added)
        if (added.ready) started?.scheduler?.requestSync()
        log.i(TAG, "Source ${added.id} (${added.kind}) on ${hostOf(added.connection.serverUrl)} added")
        return added
    }

    /** New credentials for the same account: the photos and settings stay, a sync follows soon. */
    fun reconnect(
        id: String,
        connection: Connection,
    ) {
        configs.reconnect(id, connection) ?: return
        running[id]?.scheduler?.configChanged()
        log.i(TAG, "Source $id has new credentials")
    }

    /** Validates and applies a partial change of [id], then lets that source sync again soon. */
    fun patch(
        id: String,
        patch: JsonObject,
    ): StoredSource {
        val next = configs.patch(id, patch)
        running[id]?.scheduler?.configChanged()
        return next
    }

    /** `false` if a sync of [id] is already running. A 404 for an unknown ID, a 409 for an account without a folder. */
    fun requestSync(id: String): Boolean {
        val config = configs.get(id) ?: throw HttpException.notFound("No such source")
        if (kindOf(config)?.needsFolder == true && config.folder.isEmpty()) {
            throw HttpException(409, "source_no_folder", "Choose a folder first")
        }
        return running[id]?.scheduler?.requestSync() ?: false
    }

    /**
     * Removes [id]: stops its sync, asks the server to revoke its credentials, deletes them and removes its photos,
     * skipped files and originals. Other sources and the local photos are not touched. `false` for an unknown ID.
     */
    fun remove(id: String): Boolean {
        val config = configs.get(id) ?: return false
        val gone = running.remove(id)
        gone?.scheduler?.stop()
        try {
            kindOf(config)?.revoke(config)
        } catch (e: Exception) {
            log.w(TAG, "The credentials of $id could not be revoked on the server: ${e.message}")
        }
        // The configuration goes first: a sync that is still running sees it gone and applies nothing.
        configs.remove(id)
        results.remove(id)
        val removed =
            index.edit { edit ->
                val photos = edit.entries(id)
                photos.forEach { edit.remove(it.id) }
                edit.skipped(id).forEach { edit.unskip(id, it.path) }
                photos
            }
        removed.forEach(onPhotoRemoved)
        (gone?.source?.originals ?: OriginalsStore(File(originalsDir, id), freeBytes)).delete()
        onSourceRemoved(config)
        log.i(TAG, "Source $id removed, ${removed.size} photos removed")
        return true
    }

    /** The network changed: every source that waits for a retry tries sooner. */
    fun networkChanged() {
        running.values.forEach { it.scheduler.networkChanged() }
    }

    /** The original of [entry] for a rendering, fetched again if it was removed from the cache. */
    fun original(entry: PhotoEntry): File =
        source(entry.source)?.original(entry)
            ?: throw SourceException(SourceException.Kind.NOT_FOUND, "The source of ${entry.path} is not there")

    private fun launch(config: StoredSource): Running? {
        val kind = kindOf(config)
        if (kind == null) {
            log.w(TAG, "Source ${config.id} has the unknown kind ${config.kind} and does not sync")
            return null
        }
        val id = config.id
        val source =
            RemoteSource(
                id,
                { configs.get(id) },
                kind,
                index,
                engine,
                OriginalsStore(File(originalsDir, id), freeBytes),
                onPhotoRemoved,
                log,
            )
        val scheduler = SourceScheduler(scope, source, { configs.get(id) }, results, syncLock, clock, log)
        val started = Running(source, scheduler)
        running[id] = started
        scheduler.start()
        return started
    }

    private companion object {
        const val TAG = "sources"

        /** The host for the log: the address of a share holds no token, but the log names no more than it needs. */
        fun hostOf(serverUrl: String): String =
            try {
                URI(serverUrl).host ?: serverUrl
            } catch (e: URISyntaxException) {
                serverUrl
            }
    }
}
