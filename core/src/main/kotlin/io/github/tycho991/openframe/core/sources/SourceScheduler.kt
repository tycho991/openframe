package io.github.tycho991.openframe.core.sources

import io.github.tycho991.openframe.core.library.PhotoSource
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/** When to sync. Pure, so that it is tested without time passing. */
object SyncSchedule {
    const val FIRST_SYNC_MS = 30_000L
    const val RETRY_FIRST_MS = 60_000L
    private const val MAX_DOUBLINGS = 5

    /** A change of the configuration, or a connection that was just made, syncs soon but not at once. */
    const val CONFIG_CHANGE_MS = 2_000L

    /** A network that came back shortens a waiting retry to this. */
    const val NETWORK_BACK_MS = 10_000L

    /** The wait after the [failures]th failure in a row: 60 s, then doubling, never longer than the interval. */
    fun retryDelayMs(
        failures: Int,
        intervalMs: Long,
    ): Long = minOf(intervalMs, RETRY_FIRST_MS shl minOf(maxOf(failures, 1) - 1, MAX_DOUBLINGS))
}

/**
 * Runs the sync of one source: once soon after the start, then every interval, earlier after a failure, and on
 * request. One coroutine does it all, so two syncs of the source never overlap; [syncLock] is shared by the
 * schedulers of all sources, so that only one sync runs on the device at a time. The result is kept as [status].
 */
class SourceScheduler(
    private val scope: CoroutineScope,
    private val source: PhotoSource,
    /** The current configuration, `null` once the source is removed. */
    private val config: () -> StoredSource?,
    private val results: SyncResultStore,
    private val syncLock: Mutex,
    private val clock: Clock,
    private val log: AppLog,
) {
    private enum class Wake { REQUEST, CONFIG, NETWORK }

    private val wake = Channel<Wake>(Channel.CONFLATED)
    private val _status = MutableStateFlow(SourceStatus(false, StoredSyncResult()))

    val status: StateFlow<SourceStatus> = _status.asStateFlow()

    private var failures = 0
    private var job: Job? = null

    fun start() {
        _status.value = SourceStatus(false, results.get(source.id))
        job = scope.launch { loop() }
    }

    /** Ends the schedule, when the source is removed. A sync that is running is cancelled. */
    fun stop() {
        job?.cancel()
    }

    /**
     * Asks for a sync now. `false` if one is already running, asked for or waiting for another source; the API says so. The
     * status shows the sync as running at once, before the loop has picked it up.
     */
    @Synchronized
    fun requestSync(): Boolean {
        if (_status.value.syncing || _status.value.waiting) return false
        _status.value = _status.value.copy(syncing = true)
        wake.trySend(Wake.REQUEST)
        return true
    }

    /** The configuration changed or the source got new credentials: sync soon. */
    fun configChanged() {
        wake.trySend(Wake.CONFIG)
    }

    /** The network changed; a waiting retry comes sooner. */
    fun networkChanged() {
        wake.trySend(Wake.NETWORK)
    }

    private suspend fun loop() {
        var wait: Long? = SyncSchedule.FIRST_SYNC_MS
        while (scope.isActive) {
            val active = config()?.ready == true
            val timeout = if (active) wait else null
            val reason = if (timeout == null) wake.receive() else withTimeoutOrNull(timeout) { wake.receive() }
            when (reason) {
                null -> {
                    sync()
                }

                Wake.REQUEST -> {
                    sync()
                }

                Wake.CONFIG -> {
                    // A moment to let a series of changes finish, then a sync with the new settings.
                    wait = SyncSchedule.CONFIG_CHANGE_MS
                    continue
                }

                Wake.NETWORK -> {
                    // Only a frame that is waiting for a retry has a reason to hurry.
                    wait = if (failures > 0) minOf(wait ?: Long.MAX_VALUE, SyncSchedule.NETWORK_BACK_MS) else wait
                    continue
                }
            }
            val interval = (config()?.intervalMinutes ?: StoredSource.DEFAULT_INTERVAL_MINUTES) * 60_000L
            wait = if (failures > 0) SyncSchedule.retryDelayMs(failures, interval) else interval
        }
    }

    private suspend fun sync() {
        if (config()?.ready != true) {
            _status.value = _status.value.copy(syncing = false)
            return
        }
        _status.value = _status.value.copy(syncing = true)
        val result =
            try {
                if (!syncLock.tryLock()) {
                    // Another source syncs: this one waits and says so, it does not run yet.
                    _status.value = _status.value.copy(syncing = false, waiting = true)
                    syncLock.lock()
                }
                _status.value = _status.value.copy(syncing = true, waiting = false)
                // A failure leaves the lock at once, so a source that cannot be reached does not hold the others up.
                val counts =
                    try {
                        source.scan()
                    } finally {
                        syncLock.unlock()
                    }
                failures = 0
                StoredSyncResult(clock.millis(), true, null, counts.added, counts.updated, counts.removed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SourceException) {
                failures++
                log.w(TAG, "Sync of ${source.id} failed: ${e.message}")
                StoredSyncResult(clock.millis(), false, e.message, 0, 0, 0)
            } catch (e: Exception) {
                failures++
                log.e(TAG, "Sync of ${source.id} failed unexpectedly", e)
                StoredSyncResult(clock.millis(), false, "The sync failed unexpectedly (${e.javaClass.simpleName})", 0, 0, 0)
            }
        update(result)
    }

    private fun update(result: StoredSyncResult) {
        _status.value = SourceStatus(false, result)
        // A source removed while its sync ran has no result to keep.
        if (config() != null) results.put(source.id, result)
    }

    private companion object {
        const val TAG = "sources"
    }
}
