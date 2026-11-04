package io.github.tycho991.openframe.core.health

import io.github.tycho991.openframe.core.testing.TestClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration

/** Spec `kiosk-host`: Self-healing, Safe mode after repeated crashes, Leaving safe mode, Planned process end. */
class RunJournalTest {
    @TempDir
    lateinit var dir: File

    private val clock = TestClock()
    private val file get() = File(dir, "state/health/runs.json")
    private lateinit var journal: RunJournal

    /** A new process: a new journal on the same file, as after a restart of the app. */
    private fun start(
        elapsedMs: Long = 60_000,
        bootId: String? = "boot-1",
        installId: String = "100:1700000000000",
    ): HealthState {
        journal = RunJournal(file)
        return journal.onProcessStart(clock.millis(), elapsedMs, bootId, installId)
    }

    private fun crash() = journal.onJavaCrash("java.lang.IllegalStateException")

    /** Three Java crashes, each within minutes of its start; returns the start that decides. */
    private fun intoSafeMode(): HealthState {
        var state = start(elapsedMs = 10_000)
        repeat(3) {
            crash()
            state = start(elapsedMs = 20_000L + it * 10_000)
        }
        return state
    }

    @Test
    fun `How the previous run ended`() {
        assertEquals(RunEnd.FIRST, start().previousEnd)

        crash()
        val crashed = start(elapsedMs = 70_000)
        assertEquals(RunEnd.CRASHED, crashed.previousEnd)
        assertEquals("java.lang.IllegalStateException", crashed.previousCrash)

        assertEquals(RunEnd.DIED, start(elapsedMs = 80_000).previousEnd, "a native crash or a kill leaves no record")

        assertEquals(RunEnd.REBOOTED, start(elapsedMs = 90_000, bootId = "boot-2").previousEnd, "another boot_id")
        assertTrue(start(elapsedMs = 95_000, bootId = "boot-2").bootIdReadable)

        // Without boot_id: an uptime below the one the previous run recorded, also when both starts sit near the same
        // uptime after a boot, because the previous run recorded its uptime once a minute.
        start(elapsedMs = 20_000, bootId = null)
        journal.recordUptime(85_000)
        val rebooted = start(elapsedMs = 22_000, bootId = null)
        assertEquals(RunEnd.REBOOTED, rebooted.previousEnd)
        assertFalse(rebooted.bootIdReadable)

        // The same version code with a new lastUpdateTime, as a debug build installed again.
        assertEquals(RunEnd.REPLACED, start(installId = "100:1700000999000", bootId = null).previousEnd)
    }

    @Test
    fun `A planned end counts only while it is valid`() {
        start(elapsedMs = 100_000)
        journal.plannedExit("update", 100_000, validForMs = 15 * 60_000)
        assertEquals(RunEnd.PLANNED, start(elapsedMs = 130_000).previousEnd)

        journal.plannedExit("device administration", 130_000, validForMs = 10_000)
        assertEquals(RunEnd.DIED, start(elapsedMs = 200_000).previousEnd, "an expired record does not count")

        journal.plannedExit("update", 200_000, validForMs = 60_000)
        journal.cancelPlannedExit()
        assertEquals(RunEnd.DIED, start(elapsedMs = 210_000).previousEnd, "a cancelled one neither")
    }

    @Test
    fun `Three early ends in a row lead to safe mode`() {
        val safe = intoSafeMode()
        assertEquals(HealthMode.SAFE, safe.mode)
        assertEquals(3, safe.recentEarlyEnds)
        assertTrue(safe.safeMode!!.reason.contains("3 early ends"))

        // Safe mode goes on after a device restart.
        assertEquals(HealthMode.SAFE, start(elapsedMs = 5_000, bootId = "boot-2").mode)
    }

    @Test
    fun `A stable run, a device restart, a planned end and a new installation reset the count`() {
        start()
        crash()
        start()
        crash()
        assertEquals(2, start().recentEarlyEnds)
        journal.markStable()
        crash()
        assertEquals(0, start().recentEarlyEnds, "a crash after 10 minutes is no early end")

        crash()
        assertEquals(1, start(elapsedMs = 61_000).recentEarlyEnds)
        assertEquals(0, start(elapsedMs = 1_000, bootId = "boot-2").recentEarlyEnds, "a device restart")
        crash()
        assertEquals(1, start(elapsedMs = 2_000, bootId = "boot-2").recentEarlyEnds)
        journal.plannedExit("restart", 2_000, 60_000)
        assertEquals(0, start(elapsedMs = 3_000, bootId = "boot-2").recentEarlyEnds, "a planned end")
        crash()
        assertEquals(1, start(elapsedMs = 4_000, bootId = "boot-2").recentEarlyEnds)
        assertEquals(0, start(elapsedMs = 5_000, bootId = "boot-2", installId = "101:1").recentEarlyEnds, "a new installation")
    }

    @Test
    fun `A new installation leaves safe mode`() {
        intoSafeMode()
        val replaced = start(installId = "101:1800000000000")
        assertEquals(RunEnd.REPLACED, replaced.previousEnd)
        assertEquals(HealthMode.NORMAL, replaced.mode)
        assertNull(replaced.safeMode)
    }

    @Test
    fun `The probe comes after 1, 2, 4 up to 24 hours, and an early end in the probation run returns to safe mode`() {
        intoSafeMode()
        val waits = mutableListOf<Long>()
        repeat(7) {
            val at = journal.probeAtMs()!!
            waits += (at - clock.millis()) / RunJournal.HOUR_MS
            clock.advance(Duration.ofMillis(at - clock.millis()))
            journal.requestNormalStart()
            val probation = start()
            assertEquals(HealthMode.NORMAL, probation.mode)
            assertTrue(probation.probation)
            assertNull(journal.probeAtMs(), "no probe in normal mode")
            // It crashes again within minutes: straight back into safe mode.
            crash()
            assertEquals(HealthMode.SAFE, start().mode)
        }
        assertEquals(listOf(1L, 2L, 4L, 8L, 16L, 24L, 24L), waits)

        // A probation run that becomes stable ends safe mode for good.
        clock.advance(Duration.ofMillis(journal.probeAtMs()!! - clock.millis()))
        journal.requestNormalStart()
        start()
        journal.markStable()
        crash()
        val after = start()
        assertEquals(HealthMode.NORMAL, after.mode)
        assertEquals(0, after.recentEarlyEnds, "a crash after a stable run is no early end")
    }

    @Test
    fun `A broken or unwritable runs json gives normal mode without throwing`() {
        file.parentFile.mkdirs()
        file.writeText("{ damaged")
        assertEquals(HealthMode.NORMAL, start().mode)

        val blocked = RunJournal(File(File(dir, "a-file").apply { writeText("x") }, "runs.json"))
        assertEquals(HealthMode.NORMAL, blocked.onProcessStart(0, 0, null, "1").mode)
        blocked.recordUptime(1)
        blocked.onJavaCrash("x")
        blocked.markStable()
        blocked.plannedExit("x", 0, 1)
        blocked.requestNormalStart()
    }
}
