package io.github.tycho991.openframe.core.update

import io.github.tycho991.openframe.core.health.Health
import io.github.tycho991.openframe.core.health.RunEnd
import io.github.tycho991.openframe.core.health.RunJournal
import io.github.tycho991.openframe.core.platform.InstallResult
import io.github.tycho991.openframe.core.testing.Fixtures
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.Duration

/**
 * Spec `app-update`: No dialog without a request, Installation dialog, Unanswered dialog, Installation on request of a
 * client, Pause after a declined dialog, Installation not allowed and Installation is a planned end (design decision 6).
 */
class UpdateInstallTest {
    @TempDir
    lateinit var dir: File

    private val h by lazy { UpdateHarness(dir) }

    @AfterEach
    fun stop() = h.close()

    private val calls get() = h.installer.calls.toList()

    /** Asks for the installation as [client] and lets the dialog open. */
    private fun install(client: String? = "\"Phone\""): InstallRequest {
        val answer = h.manager.install(client)
        h.scope.testScheduler.runCurrent()
        return answer
    }

    private val journalFile get() = File(dir, "runs.json")

    /** The run journal of a new process start, as `FrameApp` creates it. */
    private fun startProcess(boot: String? = "boot-1"): Health {
        val journal = RunJournal(journalFile)
        val state = journal.onProcessStart(h.clock.millis(), h.clock.elapsed, boot, "300:1")
        return Health(journal, state, boot) { h.clock.elapsed }.also { h.health = it }
    }

    /** Spec `app-update`: No dialog without a request › Ready update, nobody asks. */
    @Test
    fun `Ready update, nobody asks`() {
        h.ready()
        h.hours(24)
        assertEquals(UpdatePhase.READY, h.state.phase)
        assertTrue(calls.none { it.startsWith("create") })
    }

    /** Spec `app-update`: Installation on request of a client › Install on the frame. */
    @Test
    fun `Install on the frame`() {
        h.health = Health.normal("boot-1")
        h.ready()
        assertEquals(InstallRequest.Accepted, install())
        assertEquals(listOf("create 1", "commit 1"), calls)
        assertArrayEquals(Fixtures.apk("0.4.0"), h.installer.committed[1])
        assertEquals(UpdatePhase.INSTALLING, h.state.phase)
        assertEquals(h.clock.monotonic + UpdateManager.DEADLINE_MS, h.state.installUntilMono)
        val req = h.stored().installRequested!!
        assertEquals(1, req.sessionId)
        assertEquals("boot-1", req.bootId)
        assertEquals(h.clock.elapsed + UpdateManager.DEADLINE_MS, req.deadlineElapsed)
        assertTrue(h.logged("Installation of 0.4.0 requested by \"Phone\"").isNotEmpty())
        assertEquals(InstallRequest.InProgress, install())
    }

    /** Spec `app-update`: Installation on request of a client › Nothing ready. */
    @Test
    fun `Nothing ready`() {
        h.start()
        assertEquals(InstallRequest.NotReady, install())
        assertTrue(calls.isEmpty())
        assertEquals(UpdatePhase.IDLE, h.state.phase)
    }

    /** Spec `app-update`: Installation not allowed › Unknown sources off. */
    @Test
    fun `Unknown sources off`() {
        h.ready()
        h.installer.allowed = false
        assertEquals(false, h.manager.current().installAllowed)
        assertEquals(InstallRequest.NotAllowed, install())
        assertEquals(InstallRequest.NotAllowed, install(null))
        assertTrue(calls.isEmpty())
        assertEquals(UpdatePhase.READY, h.state.phase)
    }

    /** Spec `app-update`: Installation dialog › Cancelled; Installation is a planned end (withdrawn). */
    @Test
    fun `Cancelled`() {
        startProcess()
        h.ready()
        install(null)
        h.installer.answer(1, InstallResult.Cancelled)
        assertEquals(UpdatePhase.READY, h.state.phase)
        assertEquals(UpdateErrorCode.INSTALL_CANCELLED, h.state.error?.code)
        assertNull(h.stored().installRequested)
        // The planned end is withdrawn: an end now would be a crash again.
        assertEquals(RunEnd.DIED, startProcess().state.value.previousEnd)
    }

    @Test
    fun `A fast failure right after commit is applied`() {
        h.ready()
        h.installer.answerOnCommit = InstallResult.Failed("INSTALL_FAILED_INSUFFICIENT_STORAGE")
        install()
        assertEquals(UpdatePhase.READY, h.state.phase)
        assertEquals(UpdateErrorCode.INSTALL_FAILED, h.state.error?.code)
        assertTrue("INSTALL_FAILED_INSUFFICIENT_STORAGE" in h.state.error!!.message)
        // No pause after a failure.
        assertEquals(InstallRequest.Accepted, install())
    }

    @Test
    fun `A commit that throws ends the dialog and abandons its session`() {
        h.ready()
        h.installer.commitFailure = IOException("No space left")
        install()
        assertEquals(listOf("create 1", "commit 1", "abandon 1"), calls)
        assertEquals(UpdatePhase.READY, h.state.phase)
        assertEquals(UpdateErrorCode.INSTALL_FAILED, h.state.error?.code)
    }

    /** Spec `app-update`: Installation dialog › App restarted during the dialog. */
    @Test
    fun `App restarted during the dialog`() {
        h.ready()
        install()
        h.minutes(2)
        h.restart()
        assertEquals(UpdatePhase.INSTALLING, h.state.phase)
        assertTrue(calls.none { it.startsWith("abandon") })
        h.installer.answer(1, InstallResult.Cancelled)
        assertEquals(UpdatePhase.READY, h.state.phase)
        assertEquals(UpdateErrorCode.INSTALL_CANCELLED, h.state.error?.code)
    }

    @Test
    fun `A result that arrives before the state is loaded is applied after loading`() {
        h.ready()
        install()
        h.stop()
        h.start { it.onInstallResult(1, InstallResult.Cancelled) }
        assertEquals(UpdatePhase.READY, h.state.phase)
        assertEquals(UpdateErrorCode.INSTALL_CANCELLED, h.state.error?.code)
        assertTrue(calls.none { it.startsWith("abandon") })
    }

    @Test
    fun `A new frame during the dialog continues with the rest of the deadline and a renewed planned end`() {
        startProcess()
        h.ready()
        install()
        h.minutes(4)
        assertEquals(RunEnd.PLANNED, startProcess().state.value.previousEnd)
        h.restart()
        assertEquals(UpdatePhase.INSTALLING, h.state.phase)
        assertEquals(h.clock.monotonic + Duration.ofMinutes(6).toMillis(), h.state.installUntilMono)
        h.minutes(1)
        // Ended again: still planned, because the new run announced the end again.
        assertEquals(RunEnd.PLANNED, startProcess().state.value.previousEnd)
        h.restart()
        h.minutes(4)
        assertEquals(UpdatePhase.INSTALLING, h.state.phase)
        h.minutes(1)
        assertEquals(UpdatePhase.READY, h.state.phase)
        assertEquals(UpdateErrorCode.INSTALL_UNANSWERED, h.state.error?.code)
        assertEquals(listOf("create 1", "commit 1", "abandon 1"), calls)
    }

    @Test
    fun `A session left without a request, after an end between creating and saving, is abandoned`() {
        h.installer.createSession()
        h.ready()
        assertEquals(listOf("create 1", "abandon 1"), calls)
        assertEquals(UpdatePhase.READY, h.state.phase)
    }

    /** Spec `app-update`: Unanswered dialog › Request from the sofa, nobody at the frame. */
    @Test
    fun `Request from the sofa, nobody at the frame`() {
        startProcess()
        h.ready()
        install()
        h.minutes(9)
        assertEquals(UpdatePhase.INSTALLING, h.state.phase)
        h.minutes(1)
        assertEquals(listOf("create 1", "commit 1", "abandon 1"), calls)
        assertEquals(UpdatePhase.READY, h.state.phase)
        assertEquals(UpdateErrorCode.INSTALL_UNANSWERED, h.state.error?.code)
        // Android's late cancel of the abandoned session does not turn it into a cancel.
        h.installer.answer(1, InstallResult.Cancelled)
        assertEquals(UpdateErrorCode.INSTALL_UNANSWERED, h.state.error?.code)
        assertEquals(RunEnd.DIED, startProcess().state.value.previousEnd)
    }

    /** Spec `app-update`: Unanswered dialog › Device restarted (by `boot_id`). */
    @Test
    fun `Device restarted, by boot_id`() {
        h.health = Health.normal("boot-1")
        h.ready()
        install()
        h.minutes(1)
        h.stop()
        h.health = Health.normal("boot-2")
        h.start()
        assertEquals(listOf("create 1", "commit 1", "abandon 1"), calls)
        assertEquals(UpdatePhase.READY, h.state.phase)
        assertEquals(UpdateErrorCode.INSTALL_UNANSWERED, h.state.error?.code)
        // No pause in the new run.
        assertEquals(InstallRequest.Accepted, install())
    }

    /** Spec `app-update`: Unanswered dialog › Device restarted (by a lower uptime, without `boot_id`). */
    @Test
    fun `Device restarted, by a lower uptime`() {
        h.ready()
        install()
        h.stop()
        h.clock.elapsed = 30_000
        h.start()
        assertTrue("abandon 1" in calls)
        assertEquals(UpdateErrorCode.INSTALL_UNANSWERED, h.state.error?.code)
    }

    @Test
    fun `Without an open session and without a result the dialog counts as unanswered`() {
        h.ready()
        install()
        h.stop()
        h.installer.answer(1, InstallResult.Cancelled)
        h.start()
        assertEquals(UpdatePhase.READY, h.state.phase)
        assertEquals(UpdateErrorCode.INSTALL_UNANSWERED, h.state.error?.code)
    }

    /** Spec `app-update`: Pause after a declined dialog › Request right after a cancel. */
    @Test
    fun `Request right after a cancel`() {
        h.ready()
        install(null)
        h.installer.answer(1, InstallResult.Cancelled)
        h.minutes(5)
        assertEquals(InstallRequest.Cooldown, install("\"Tablet\""))
        assertTrue(h.logged("requested by \"Tablet\" refused: install_cooldown").isNotEmpty())
        assertEquals(listOf("create 1", "commit 1"), calls)
        // The device menu stays available.
        assertEquals(InstallRequest.Accepted, install(null))
        h.installer.answer(2, InstallResult.Cancelled)
        h.minutes(29)
        assertEquals(InstallRequest.Cooldown, install())
        h.minutes(1)
        assertEquals(InstallRequest.Accepted, install())
    }

    @Test
    fun `A pause follows an unanswered dialog as well`() {
        h.ready()
        install()
        h.minutes(10)
        assertEquals(InstallRequest.Cooldown, install())
    }

    /** Spec `app-update`: Installation is a planned end › App ended while the dialog is open. */
    @Test
    fun `App ended while the dialog is open`() {
        startProcess()
        h.ready()
        install()
        h.minutes(3)
        val next = startProcess()
        assertEquals(RunEnd.PLANNED, next.state.value.previousEnd)
        assertEquals(0, next.state.value.recentEarlyEnds)
    }

    /** Spec `kiosk-host` and `app-update`: after Android replaced the app, the next start says so. */
    @Test
    fun `Updated to the new version`() {
        h.ready()
        install()
        h.stop()
        h.platform.appVersion = "0.4.0"
        h.platform.appVersionCode = 400
        h.start()
        assertTrue(h.logged("Updated to 0.4.0").isNotEmpty())
        assertEquals(UpdatePhase.IDLE, h.state.phase)
        assertNull(h.stored().installRequested)
        assertEquals(
            emptyList<String>(),
            h.updates
                .listFiles()
                .orEmpty()
                .map { it.name },
        )
    }

    /** Spec `app-update`: Updates in safe mode › Fix for a crashing release (the request of a client). */
    @Test
    fun `A client installs the fix in safe mode`() {
        h.health = Health.safe(h.clock.millis())
        h.fake.publish("0.4.0")
        h.start()
        assertEquals(UpdatePhase.READY, h.state.phase)
        assertEquals(InstallRequest.Accepted, install())
        assertEquals(listOf("create 1", "commit 1"), calls)
    }
}
