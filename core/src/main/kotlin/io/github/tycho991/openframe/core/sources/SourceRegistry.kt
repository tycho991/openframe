package io.github.tycho991.openframe.core.sources

import io.github.tycho991.openframe.core.http.HttpException
import io.github.tycho991.openframe.core.library.LocalPhotoSource
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.PhotoIndexStore
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.Clock
import io.github.tycho991.openframe.core.platform.MediaEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonObject
import java.io.File
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
    /**
     * Photos left the library: their renderings are not needed any more. Called once with all photos removed together,
     * never with an empty list.
     */
    private val onPhotosRemoved: (List<PhotoEntry>) -> Unit,
    private val clock: Clock,
    private val log: AppLog,
    /** Called after a source was removed, for files that an older version kept for it. */
    private val onSourceRemoved: (StoredSource) -> Unit = {},
    private val freeBytes: (File) -> Long = { it.usableSpace },
    /** After each sync of a source, also one that failed: what arrived before the failure stays. */
    private val onSynced: () -> Unit = {},
) {
    private class Running(
        val source: RemoteSource,
        val scheduler: SourceScheduler,
    )

    private val kinds = kinds.associateBy { it.id }
    private val running = ConcurrentHashMap<String, Running>()
    private val syncLock = Mutex()

    /**
     * Starts the schedules of the stored sources. Photos, skipped files and originals of a source that is not stored any
     * more (its configuration was lost) go first: no source would sync, render or remove them. Not with
     * [removeOrphans] `false`, for an unreadable `sources.json` (spec `remote-sources`, "Photos kept when the sources
     * cannot be read"): its sources are not lost, only not readable now.
     */
    fun start(
        removeOrphans: Boolean = true,
        launch: Boolean = true,
    ) {
        if (removeOrphans) removeOrphans()
        launching = launch
        if (launch) for (config in configs.sources.value) launch(config)
    }

    /**
     * `false` in safe mode: sources are stored on add, patch and reconnect, but no scheduler starts and no sync is
     * requested; they start with the next normal run (spec `kiosk-host`, "Safe mode after repeated crashes").
     */
    @Volatile private var launching = true

    fun kindOf(source: StoredSource): SourceKind? = kinds[source.kind]

    /** The source with [id] for a rendering, `null` for an unknown one. */
    fun source(id: String): RemoteSource? = running[id]?.source

    fun status(id: String): SourceStatus = running[id]?.scheduler?.status?.value ?: SourceStatus(false, results.get(id))

    /** Stores and starts [source]. One that can sync at once does, so that its first sync shows as running. */
    fun add(source: StoredSource): StoredSource {
        val added = configs.add(source)
        val started = if (launching) launch(added) else null
        if (added.ready) started?.scheduler?.requestSync()
        log.i(TAG, "Source ${added.id} (${added.kind}) on ${SourceConfigStore.hostOf(added.connection.serverUrl)} added")
        return added
    }

    /**
     * New credentials for the same account: the photos and settings stay, a sync follows soon. The credentials they
     * replace are revoked on the server in the background once a running sync of the source has ended (spec
     * `nextcloud-source`, "No orphaned app passwords"), unless the server already refused them.
     */
    fun reconnect(
        id: String,
        connection: Connection,
    ) {
        val old = configs.get(id) ?: return
        val refused = refusedCredentials(id)
        configs.reconnect(id, connection) ?: return
        val scheduler = running[id]?.scheduler
        scheduler?.configChanged()
        log.i(TAG, "Source $id has new credentials")
        if (old.connection == connection) return
        // A refusal of the old credentials says nothing about these: they are revoked when they are replaced in turn.
        if (scheduler != null) {
            scheduler.credentialsReplaced()
        } else {
            results.get(id).takeIf { it.lastSyncAtMs != null }?.let { results.put(id, it.copy(credentialsReplaced = true)) }
        }
        if (refused) return
        val kind = kindOf(old) ?: return
        scope.launch {
            scheduler?.awaitNoSync()
            revoke(id, kind, old)
        }
    }

    /** Runs [block], a request to revoke credentials on a server, in the background, so that a silent server holds up nothing. */
    fun revokeLater(
        what: String,
        block: () -> Unit,
    ) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                log.w(TAG, "$what could not be revoked on the server: ${e.message}")
            }
        }
    }

    /** The last sync of [id] failed because the server refused its credentials for the folder: sending them again is useless. */
    fun refusedCredentials(id: String): Boolean =
        status(id).last.let { it.rejected && it.errorCode == SourceException.Kind.UNAUTHORIZED.code && !it.credentialsReplaced }

    private fun revoke(
        id: String,
        kind: SourceKind,
        config: StoredSource,
    ) {
        try {
            kind.revoke(config)
        } catch (e: Exception) {
            log.w(TAG, "The credentials of $id could not be revoked on the server: ${e.message}")
        }
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
     * Removes [id]: stops its sync, deletes its credentials and removes its photos, skipped files and originals, then asks
     * the server to revoke the credentials in the background, so that a server that does not answer does not hold up the
     * request. Other sources and the local photos are not touched. `false` for an unknown ID, also for the second of two
     * removals at once.
     */
    @Synchronized
    fun remove(id: String): Boolean {
        // Asked first: the result goes with the source.
        val refused = refusedCredentials(id)
        // The configuration goes first: a sync that is still running sees it gone and applies nothing.
        val config = configs.remove(id) ?: return false
        val gone = running.remove(id)
        gone?.scheduler?.stop()
        results.remove(id)
        val removed =
            index.edit { edit ->
                val photos = edit.entries(id)
                photos.forEach { edit.remove(it.id) }
                edit.skipped(id).forEach { edit.unskip(id, it.path) }
                photos
            }
        if (removed.isNotEmpty()) onPhotosRemoved(removed)
        (gone?.source?.originals ?: OriginalsStore(File(originalsDir, id), freeBytes)).delete()
        onSourceRemoved(config)
        log.i(TAG, "Source $id removed, ${removed.size} photos removed")
        // Credentials the server refused are not sent again: each attempt counts as a failed login there.
        if (!refused) kindOf(config)?.let { kind -> scope.launch { revoke(id, kind, config) } }
        return true
    }

    /** The network changed: every source that waits for a retry tries sooner. */
    fun networkChanged() {
        running.values.forEach { it.scheduler.networkChanged() }
    }

    /**
     * The original of [entry] for a rendering, fetched again if it was removed from the cache, and pinned with [pin]. A
     * source whose access was rejected sends no request with the refused credentials (spec "Rejected access" › Removed
     * original wanted): only an original that is still stored is given.
     */
    fun original(
        entry: PhotoEntry,
        pin: Boolean = false,
    ): File {
        val running =
            running[entry.source] ?: throw SourceException(SourceException.Kind.NOT_FOUND, "The source of ${entry.path} is not there")
        if (running.scheduler.rejected && !running.source.originals.has(entry)) {
            val last = running.scheduler.status.value.last
            val kind = SourceException.Kind.entries.firstOrNull { it.code == last.errorCode } ?: SourceException.Kind.UNAUTHORIZED
            throw SourceException(kind, "The server refused access to the source ${entry.source}; ${entry.path} is not fetched")
        }
        return running.source.original(entry, pin)
    }

    private fun removeOrphans() {
        val known = configs.sources.value.mapTo(HashSet()) { it.id } + LocalPhotoSource.SOURCE_ID
        val orphans =
            index.edit { edit ->
                val photos = edit.photos.values.filter { it.source !in known }
                photos.forEach { edit.remove(it.id) }
                edit.skipped.values
                    .filter { it.source !in known }
                    .forEach { edit.unskip(it.source, it.path) }
                photos
            }
        if (orphans.isNotEmpty()) onPhotosRemoved(orphans)
        originalsDir
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name !in known }
            .forEach { OriginalsStore(it, freeBytes).delete() }
        if (orphans.isNotEmpty()) log.w(TAG, "${orphans.size} photos of sources that are not stored any more removed")
    }

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
                onPhotosRemoved,
                log,
                onStale = { running[id]?.scheduler?.staleOriginal() },
                clock = clock,
            )
        val scheduler = SourceScheduler(scope, source, { configs.get(id) }, results, syncLock, clock, log, onSynced)
        val started = Running(source, scheduler)
        running[id] = started
        scheduler.start()
        return started
    }

    private companion object {
        const val TAG = "sources"
    }
}
