package io.github.tycho991.openframe.core.update

import io.github.tycho991.openframe.core.health.Health
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.settings.Settings
import io.github.tycho991.openframe.core.store.AtomicFile
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.store.StoreJson
import io.github.tycho991.openframe.core.testing.FakeAppInstaller
import io.github.tycho991.openframe.core.testing.FakePlatform
import io.github.tycho991.openframe.core.testing.FakeReleaseSource
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * An [UpdateManager] on the test scheduler: [advance] moves the [TestClock] and the scheduler together, [restart] starts
 * a new manager on the same files and the same [installer], as after a restart of the app. Version 0.3.0 is installed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateHarness(
    private val dir: File,
    val source: ReleaseSource = FakeReleaseSource(),
) {
    val scope = TestScope()
    val clock = TestClock(Instant.parse("2026-11-04T10:00:00Z")).apply { elapsed = 3_600_000 }
    val platform = FakePlatform(appVersion = "0.3.0", appVersionCode = 300)
    val installer = FakeAppInstaller()
    val settings = MutableStateFlow(Settings(name = "Frame"))
    val log = AppLog(clock)
    val updates = File(dir, "update")
    val storeFile = File(dir, "state/update.json")
    var health: Health = Health.normal()
    private var run: CoroutineScope? = null
    lateinit var manager: UpdateManager

    val fake: FakeReleaseSource get() = source as FakeReleaseSource
    val state: UpdateState get() = manager.state.value

    /** Starts a manager; [before] runs on it before it loads, as a broadcast that started the process would. */
    fun start(before: (UpdateManager) -> Unit = {}): UpdateManager {
        val s = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        run = s
        manager =
            UpdateManager(
                s,
                JsonStore(storeFile, StoredUpdate.serializer(), s, log),
                source,
                installer,
                platform,
                health,
                settings,
                updates,
                clock,
                log,
            )
        installer.onResult = manager::onInstallResult
        before(manager)
        manager.start()
        scope.runCurrent()
        return manager
    }

    /** Ends this manager after writing its state, as the app's process would end. */
    fun stop() {
        val m = manager
        scope.launch { m.flush() }
        scope.runCurrent()
        run?.cancel()
        scope.runCurrent()
    }

    fun restart(): UpdateManager {
        stop()
        return start()
    }

    fun close() = scope.cancel()

    /** Lets [duration] pass on the wall clock, the monotonic time, the uptime and the scheduler. */
    fun advance(duration: Duration) {
        var left = duration.toMillis()
        while (left > 0) {
            val step = minOf(left, 10_000L)
            clock.advance(Duration.ofMillis(step))
            scope.advanceTimeBy(step)
            scope.runCurrent()
            left -= step
        }
    }

    fun minutes(n: Long) = advance(Duration.ofMinutes(n))

    fun hours(n: Long) = advance(Duration.ofHours(n))

    /** `update.json` as written, after pending changes are written. */
    fun stored(): StoredUpdate {
        val m = manager
        scope.launch { m.flush() }
        scope.runCurrent()
        return StoreJson.decodeFromString(StoredUpdate.serializer(), AtomicFile(storeFile).readText()!!)
    }

    /** Writes `update.json` before [start], as a previous run left it. */
    fun store(value: StoredUpdate) {
        storeFile.parentFile.mkdirs()
        AtomicFile(storeFile).writeText(StoreJson.encodeToString(StoredUpdate.serializer(), value))
    }

    /** Lets a check run now on request and returns the state afterwards. */
    fun checkNow(): UpdateState {
        manager.check()
        scope.runCurrent()
        return state
    }

    /** Publishes 0.4.0 and lets the first automatic check find, download and verify it. */
    fun ready(version: String = "0.4.0"): UpdateState {
        fake.publish(version)
        start()
        minutes(3)
        check(state.phase == UpdatePhase.READY) { "Not ready: $state" }
        return state
    }

    fun logged(text: String): List<String> = log.recent(1000).map { it.message }.filter { text in it }
}
