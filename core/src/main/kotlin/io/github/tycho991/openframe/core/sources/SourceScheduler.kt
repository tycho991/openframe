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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** When to sync. Pure, so that it is tested without time passing. */
object SyncSchedule {
    const val FIRST_SYNC_MS = 30_000L
    const val RETRY_FIRST_MS = 60_000L
    private const val MAX_DOUBLINGS = 5

    /** A change of the configuration, or a connection that was just made, syncs soon but not at once. */
    const val CONFIG_CHANGE_MS = 2_000L

    /** A network that came back shortens a waiting retry to this. */
    const val NETWORK_BACK_MS = 10_000L

    /** The wait after rejected access (spec `remote-sources`, "Rejected access"), and the longest a server may ask for. */
    const val REJECTED_MS = 24 * 60 * 60_000L

    /** The shortest wait after HTTP 429, whatever `Retry-After` says. */
    const val THROTTLED_MIN_MS = 15 * 60_000L

    /** `Retry-After` in milliseconds, clamped to [THROTTLED_MIN_MS] to [REJECTED_MS]. */
    fun throttledMs(askedMs: Long): Long = askedMs.coerceIn(THROTTLED_MIN_MS, REJECTED_MS)

    /**
     * What is left of the day of a source whose access was rejected at [failedAtMs], at [nowMs] after a restart: clamped
     * to 0 to 24 hours, as the wall clock may jump after a boot.
     */
    fun rejectedLeftMs(
        failedAtMs: Long,
        nowMs: Long,
    ): Long = (failedAtMs + REJECTED_MS - nowMs).coerceIn(0L, REJECTED_MS)

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
 *
 * The next sync has an absolute deadline on [Clock.monotonicMillis], and the reasons to wake up are kept as flags: a
 * network change can neither restart a long wait nor overwrite a change of the configuration or a request that came
 * before it. After rejected access and after HTTP 429 with `Retry-After` the wait is fixed: a network change does not
 * shorten it, a change of the configuration or a request still tries at once.
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
    /** After each sync that ran. */
    private val onSynced: () -> Unit = {},
) {
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val _status = MutableStateFlow(SourceStatus(false, StoredSyncResult()))

    val status: StateFlow<SourceStatus> = _status.asStateFlow()

    /** A sync of this source runs at the moment, for [awaitNoSync]. */
    private val scanning = MutableStateFlow(false)

    private var pendingRequest = false
    private var pendingConfig = false
    private var pendingNetwork = false

    private var failures = 0

    /** The next sync waits a fixed time that a network change does not shorten: rejected access or `Retry-After`. */
    private var fixedWait = false

    /** An original was found stale since the last sync, see [staleOriginal]. */
    private val stale = AtomicBoolean(false)
    private var job: Job? = null

    fun start() {
        _status.value = SourceStatus(false, results.get(source.id))
        job = scope.launch { loop() }
    }

    /** Ends the schedule, when the source is removed. A sync that is running is cancelled. */
    fun stop() {
        job?.cancel()
    }

    /** The last sync ended with rejected access, also one from before a restart. */
    val rejected: Boolean get() = _status.value.last.rejected

    /**
     * Asks for a sync now. `false` if one is already running, asked for or waiting for another source; the API says so. The
     * status shows the sync as running at once, before the loop has picked it up.
     */
    @Synchronized
    fun requestSync(): Boolean {
        if (_status.value.syncing || _status.value.waiting) return false
        _status.value = _status.value.copy(syncing = true)
        pendingRequest = true
        signal.trySend(Unit)
        return true
    }

    /** The configuration changed or the source got new credentials: sync soon. */
    @Synchronized
    fun configChanged() {
        pendingConfig = true
        signal.trySend(Unit)
    }

    /** The network changed; a waiting retry comes sooner. */
    @Synchronized
    fun networkChanged() {
        pendingNetwork = true
        signal.trySend(Unit)
    }

    /**
     * An original fetched for a rendering did not match the index (spec "Refetched original matches the index"): the
     * source syncs soon, at most once until that sync has ended.
     */
    fun staleOriginal() {
        if (stale.compareAndSet(false, true)) configChanged()
    }

    /** The source got other credentials: a refusal in the last result was of the old ones. */
    fun credentialsReplaced() {
        val replaced = _status.updateAndGet { it.copy(last = it.last.copy(credentialsReplaced = true)) }.last
        if (config() != null && replaced.lastSyncAtMs != null) results.put(source.id, replaced)
    }

    /** Returns once no sync of this source runs, for new credentials that would make a running sync fail. */
    suspend fun awaitNoSync() {
        scanning.first { !it }
    }

    private class Wakes(
        val request: Boolean,
        val config: Boolean,
        val network: Boolean,
    )

    @Synchronized
    private fun takeWakes(): Wakes {
        val wakes = Wakes(pendingRequest, pendingConfig, pendingNetwork)
        pendingRequest = false
        pendingConfig = false
        pendingNetwork = false
        return wakes
    }

    private suspend fun loop() {
        val last = _status.value.last
        val firstWait =
            if (last.rejected && last.lastSyncAtMs != null) {
                fixedWait = true
                SyncSchedule.rejectedLeftMs(last.lastSyncAtMs, clock.millis())
            } else {
                SyncSchedule.FIRST_SYNC_MS
            }
        var deadline = clock.monotonicMillis() + firstWait
        while (scope.isActive) {
            val wakes = takeWakes()
            if (wakes.request) {
                deadline = syncAndPlan()
                continue
            }
            if (wakes.config) {
                // A moment to let a series of changes finish, then a sync with the new settings.
                deadline = clock.monotonicMillis() + SyncSchedule.CONFIG_CHANGE_MS
                fixedWait = false
            }
            if (wakes.network && failures > 0 && !fixedWait) {
                // Only a frame that is waiting for a retry has a reason to hurry.
                deadline = minOf(deadline, clock.monotonicMillis() + SyncSchedule.NETWORK_BACK_MS)
            }
            if (config()?.ready != true) {
                signal.receive()
                continue
            }
            val left = deadline - clock.monotonicMillis()
            // The timer of the coroutine decides when it runs out, so a clock that does not move still syncs.
            if (left <= 0 || withTimeoutOrNull(left) { signal.receive() } == null) deadline = syncAndPlan()
        }
    }

    /** Syncs and returns the deadline of the next one. */
    private suspend fun syncAndPlan(): Long {
        val wait = sync()
        return clock.monotonicMillis() + wait
    }

    /** Runs one sync and returns the wait until the next one. */
    private suspend fun sync(): Long {
        val interval = (config()?.intervalMinutes ?: StoredSource.DEFAULT_INTERVAL_MINUTES) * 60_000L
        if (config()?.ready != true) {
            _status.value = _status.value.copy(syncing = false)
            return interval
        }
        _status.value = _status.value.copy(syncing = true)
        var wait: Long? = null
        val result =
            try {
                if (!syncLock.tryLock()) {
                    // Another source syncs: this one waits and says so, it does not run yet.
                    _status.value = _status.value.copy(syncing = false, waiting = true)
                    syncLock.lock()
                }
                _status.value = _status.value.copy(syncing = true, waiting = false)
                // The credentials the sync starts with: a refusal of older ones says nothing about new ones.
                val connection = config()?.connection
                scanning.value = true
                // A failure leaves the lock at once, so a source that cannot be reached does not hold the others up.
                val counts =
                    try {
                        source.scan()
                    } catch (e: SourceException) {
                        if (e.kind in SourceErrorCodes.REJECTED && !e.fileOnly && config()?.connection == connection) {
                            throw Rejected(e)
                        }
                        throw e
                    } finally {
                        scanning.value = false
                        syncLock.unlock()
                    }
                if (failures > 0) log.recovered(TAG, "Sync of ${source.id} failed", "Sync of ${source.id} works again")
                failures = 0
                StoredSyncResult(clock.millis(), true, null, counts.added, counts.updated, counts.removed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Rejected) {
                failures++
                wait = SyncSchedule.REJECTED_MS
                log.w(TAG, "Sync of ${source.id} failed: ${e.cause.message}; access was refused, the next try is in a day")
                StoredSyncResult(clock.millis(), false, e.cause.message, 0, 0, 0, e.cause.kind.code, rejected = true)
            } catch (e: SourceException) {
                failures++
                if (e.kind == SourceException.Kind.THROTTLED && e.retryAfterMs != null) wait = e.retryAfterMs
                log.w(TAG, "Sync of ${source.id} failed: ${e.message}")
                StoredSyncResult(clock.millis(), false, e.message, 0, 0, 0, e.kind.code)
            } catch (e: Throwable) {
                // An OutOfMemoryError or anything else a fake or future engine throws must not end the schedule.
                failures++
                log.e(TAG, "Sync of ${source.id} failed unexpectedly", e)
                StoredSyncResult(
                    clock.millis(),
                    false,
                    "The sync failed unexpectedly (${e.javaClass.simpleName})",
                    0,
                    0,
                    0,
                    SourceErrorCodes.UNEXPECTED,
                )
            }
        stale.set(false)
        update(result)
        onSynced()
        fixedWait = wait != null
        return wait ?: if (failures > 0) SyncSchedule.retryDelayMs(failures, interval) else interval
    }

    /** A refusal of the folder with the credentials that are still the current ones. */
    private class Rejected(
        override val cause: SourceException,
    ) : Exception(cause.message, cause)

    private fun update(result: StoredSyncResult) {
        _status.value = SourceStatus(false, result)
        // A source removed while its sync ran has no result to keep.
        if (config() != null) results.put(source.id, result)
    }

    private companion object {
        const val TAG = "sources"
    }
}
