@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.tycho991.openframe.core.sources

import io.github.tycho991.openframe.core.library.PhotoSource
import io.github.tycho991.openframe.core.library.ScanResult
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `remote-sources`: Schedule and retry, Status, Sync on request. Time is the test scheduler's. */
class SourceSchedulerTest {
    @TempDir
    lateinit var dir: File

    /** Scans running at the moment and the most at once, over all sources. */
    private var running = 0
    private var maxRunning = 0

    private inner class Source(
        override val id: String = "nextcloud",
    ) : PhotoSource {
        var scans = 0
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        var result = ScanResult(2, 1, 0, 0)

        override suspend fun scan(): ScanResult {
            scans++
            running++
            maxRunning = maxOf(maxRunning, running)
            try {
                gate?.await()
                failure?.let { throw it }
                return result
            } finally {
                running--
            }
        }
    }

    private val scope = TestScope()
    private val log = AppLog(TestClock())
    private val source = Source()
    private val configs: SourceConfigStore by lazy {
        SourceConfigStore(JsonStore(File(dir, "sources.json"), StoredSources.serializer(), TestScope(), log)).also {
            it.load()
            it.add(FakeKind.source("nextcloud"))
        }
    }
    private val resultStore by lazy { JsonStore(File(dir, "status.json"), StoredSyncResults.serializer(), TestScope(), log) }
    private val results by lazy { SyncResultStore(resultStore).also { it.load() } }
    private val syncLock = Mutex()

    private fun schedulerOf(source: Source) =
        SourceScheduler(scope, source, { configs.get(source.id) }, results, syncLock, TestClock(), log).also { it.start() }

    private val scheduler by lazy { schedulerOf(source) }

    @AfterEach
    fun stop() = scope.cancel()

    private fun advance(ms: Long) {
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    private fun interval(minutes: Int) {
        configs.patch("nextcloud", buildJsonObject { put("intervalMinutes", JsonPrimitive(minutes)) })
    }

    private fun folder(path: String) {
        configs.patch("nextcloud", buildJsonObject { put("folder", JsonPrimitive(path)) })
    }

    @Test
    fun `The retry delays double from 60 seconds and never pass the interval`() {
        val interval = 20 * 60_000L
        assertEquals(
            listOf(60_000L, 120_000L, 240_000L, 480_000L, 960_000L, 1_200_000L, 1_200_000L),
            (1..7).map {
                SyncSchedule.retryDelayMs(it, interval)
            },
        )
        assertEquals(300_000L, SyncSchedule.retryDelayMs(4, 5 * 60_000L), "never later than a short interval")
    }

    @Test
    fun `The first sync comes 30 seconds after the start, then every interval`() {
        scheduler
        advance(29_999)
        assertEquals(0, source.scans)
        advance(1)
        assertEquals(1, source.scans)
        advance(20 * 60_000L - 1)
        assertEquals(1, source.scans)
        advance(1)
        assertEquals(2, source.scans)
    }

    @Test
    fun `A failure is tried again after 60 seconds, then 120, and a success ends the delays`() {
        scheduler
        source.failure = SourceException(SourceException.Kind.UNREACHABLE, "The server cloud is not reachable")
        advance(30_000)
        assertEquals(1, source.scans)
        advance(59_999)
        assertEquals(1, source.scans)
        advance(1)
        assertEquals(2, source.scans)
        advance(119_999)
        assertEquals(2, source.scans)
        advance(1)
        assertEquals(3, source.scans)
        source.failure = null
        advance(240_000)
        assertEquals(4, source.scans)
        // Back on the interval.
        advance(20 * 60_000L - 1)
        assertEquals(4, source.scans)
        advance(1)
        assertEquals(5, source.scans)
    }

    @Test
    fun `The status shows the time, the result, the numbers and a readable error`() {
        scheduler
        advance(30_000)
        val ok = scheduler.status.value
        assertFalse(ok.syncing)
        assertEquals(true, ok.last.lastSyncOk)
        assertEquals(Triple(2, 1, 0), Triple(ok.last.added, ok.last.updated, ok.last.removed))
        assertNull(ok.last.error)
        source.failure = SourceException(SourceException.Kind.UNAUTHORIZED, "The server refused the credentials (HTTP 401)")
        advance(20 * 60_000L)
        val failed = scheduler.status.value.last
        assertEquals(false, failed.lastSyncOk)
        assertEquals("The server refused the credentials (HTTP 401)", failed.error)
    }

    @Test
    fun `An unexpected exception is a failed sync, not the end of the schedule`() {
        scheduler
        source.failure = IllegalStateException("boom")
        advance(30_000)
        assertEquals(false, scheduler.status.value.last.lastSyncOk)
        assertTrue(
            scheduler.status.value.last.error
                .orEmpty()
                .contains("IllegalStateException"),
        )
        source.failure = null
        advance(60_000)
        assertEquals(true, scheduler.status.value.last.lastSyncOk)
    }

    @Test
    fun `A sync on request runs at once and shows as running meanwhile`() {
        scheduler
        advance(1_000)
        source.gate = CompletableDeferred()
        assertTrue(scheduler.requestSync())
        assertTrue(scheduler.status.value.syncing, "running at once, before the loop has picked it up")
        scope.runCurrent()
        assertEquals(1, source.scans)
        assertFalse(scheduler.requestSync(), "one is running")
        assertEquals(1, source.scans)
        source.gate!!.complete(Unit)
        scope.runCurrent()
        assertFalse(scheduler.status.value.syncing)
        assertEquals(true, scheduler.status.value.last.lastSyncOk)
    }

    @Test
    fun `Syncs never overlap`() {
        scheduler
        source.gate = CompletableDeferred()
        advance(30_000)
        assertEquals(1, source.scans)
        advance(60 * 60_000L)
        assertEquals(1, source.scans, "the first is still running, so no second one starts")
        source.gate!!.complete(Unit)
        scope.runCurrent()
        advance(20 * 60_000L)
        assertEquals(2, source.scans)
    }

    @Test
    fun `A switched off source does not sync, and syncs soon after it is switched on`() {
        configs.patch("nextcloud", buildJsonObject { put("enabled", JsonPrimitive(false)) })
        scheduler
        advance(2 * 60 * 60_000L)
        assertEquals(0, source.scans)
        configs.patch("nextcloud", buildJsonObject { put("enabled", JsonPrimitive(true)) })
        scheduler.configChanged()
        scope.runCurrent()
        advance(1_999)
        assertEquals(0, source.scans)
        advance(1)
        assertEquals(1, source.scans)
    }

    @Test
    fun `An account without a folder does not sync`() {
        configs.remove("nextcloud")
        configs.add(FakeKind.source("nextcloud").copy(folder = ""))
        scheduler
        advance(2 * 60 * 60_000L)
        assertEquals(0, source.scans, "nothing runs until a folder is chosen")
        scheduler.configChanged()
        scope.runCurrent()
        advance(10_000)
        assertEquals(0, source.scans)
        folder("/Photos")
        scheduler.configChanged()
        scope.runCurrent()
        advance(2_000)
        assertEquals(1, source.scans)
    }

    @Test
    fun `A removed source stops syncing and keeps no result`() {
        scheduler
        advance(30_000)
        assertEquals(1, source.scans)
        configs.remove("nextcloud")
        scheduler.stop()
        results.remove("nextcloud")
        advance(2 * 60 * 60_000L)
        assertEquals(1, source.scans)
        assertNull(results.get("nextcloud").lastSyncOk)
    }

    @Test
    fun `A failing source retries after 60 seconds while the other keeps its interval`() {
        configs.add(FakeKind.source("other"))
        val other = Source("other")
        scheduler
        val otherScheduler = schedulerOf(other)
        source.failure = SourceException(SourceException.Kind.UNREACHABLE, "The server cloud is not reachable")
        advance(30_000)
        assertEquals(1 to 1, source.scans to other.scans)
        assertEquals(false, scheduler.status.value.last.lastSyncOk)
        assertEquals(true, otherScheduler.status.value.last.lastSyncOk)
        advance(60_000)
        assertEquals(2 to 1, source.scans to other.scans, "the failing one retried, the other waits for its interval")
        advance(20 * 60_000L - 60_000)
        assertEquals(2, other.scans)
        assertTrue(source.scans > 3)
    }

    @Test
    fun `Two sources never sync at the same time`() {
        configs.add(FakeKind.source("other"))
        val other = Source("other")
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        other.gate = gate
        scheduler
        val otherScheduler = schedulerOf(other)
        advance(30_000)
        assertEquals(1 to 0, source.scans to other.scans, "the second waits for the lock")
        assertTrue(otherScheduler.status.value.waiting, "it says that it waits")
        assertFalse(otherScheduler.status.value.syncing, "and not that it syncs")
        assertFalse(otherScheduler.requestSync(), "a request while it waits starts nothing more")
        gate.complete(Unit)
        scope.runCurrent()
        assertEquals(1 to 1, source.scans to other.scans)
        assertEquals(1, maxRunning, "never both at once")
        assertEquals(true, otherScheduler.status.value.last.lastSyncOk)
        assertFalse(otherScheduler.status.value.waiting)
    }

    @Test
    fun `A failed sync frees the lock for the next source`() {
        configs.add(FakeKind.source("other"))
        val other = Source("other")
        source.failure = IllegalStateException("boom")
        scheduler
        schedulerOf(other)
        advance(30_000)
        assertEquals(1 to 1, source.scans to other.scans, "the other one ran after the failure")
        assertFalse(syncLock.isLocked)
    }

    @Test
    fun `A change of the configuration syncs after two seconds`() {
        scheduler
        advance(30_000)
        assertEquals(1, source.scans)
        interval(5)
        scheduler.configChanged()
        scope.runCurrent()
        advance(2_000)
        assertEquals(2, source.scans)
    }

    @Test
    fun `A network that comes back shortens a waiting retry but starts nothing otherwise`() {
        scheduler
        advance(30_000)
        assertEquals(1, source.scans)
        // Without a failure a network change starts nothing.
        scheduler.networkChanged()
        scope.runCurrent()
        advance(1_000)
        assertEquals(1, source.scans)
        // The next sync is due 20 minutes after the network change, here at 20:31 after the start, and it fails.
        source.failure = SourceException(SourceException.Kind.UNREACHABLE, "The server cloud is not reachable")
        advance(20 * 60_000L - 1_001)
        assertEquals(1, source.scans)
        advance(1)
        assertEquals(2, source.scans)
        // The retry would wait 60 seconds; the network brings it down to 10.
        scheduler.networkChanged()
        scope.runCurrent()
        advance(9_999)
        assertEquals(2, source.scans)
        advance(1)
        assertEquals(3, source.scans)
    }

    @Test
    fun `The result survives a restart`() {
        scheduler
        advance(30_000)
        runBlocking { resultStore.flush() }
        val again =
            SourceScheduler(TestScope(), source, {
                configs.get("nextcloud")
            }, SyncResultStore(resultStore).also { it.load() }, Mutex(), TestClock(), log)
        again.start()
        assertEquals(true, again.status.value.last.lastSyncOk)
        assertEquals(2, again.status.value.last.added)
        assertFalse(again.status.value.syncing)
    }
}
