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

/**
 * Spec `kiosk-host`: Self-healing, Safe mode after repeated crashes, Leaving safe mode, Planned process end, System
 * screen from the device menu, Android settings hidden where the firmware ends the app.
 */
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

    /** `kiosk-host` › System screen from the device menu › Several ends under a system screen. */
    @Test
    fun `Ends under a system screen count as planned however many there are`() {
        start(elapsedMs = 100_000)
        journal.systemScreenOpened("android settings", 100_000)
        var elapsed = 100_000L
        repeat(3) {
            // Each end comes minutes after its start, later than the learning window, and before the record expires.
            elapsed += 5 * 60_000
            val state = start(elapsedMs = elapsed)
            assertEquals(RunEnd.PLANNED, state.previousEnd, "end ${it + 1}")
            assertEquals(0, state.recentEarlyEnds)
            assertEquals(HealthMode.NORMAL, state.mode)
            assertTrue(state.systemScreenOpen, "the record is carried into run ${it + 2}")
            assertFalse(state.systemScreenEndsApp)
        }
    }

    /** `kiosk-host` › System screen from the device menu › Back from a system screen. */
    @Test
    fun `An end after the slideshow is back is unexplained and early`() {
        start(elapsedMs = 100_000)
        journal.systemScreenOpened("android settings", 100_000)
        journal.cancelSystemScreen()
        val state = start(elapsedMs = 160_000)
        assertEquals(RunEnd.DIED, state.previousEnd)
        assertEquals(1, state.recentEarlyEnds)
        assertFalse(state.systemScreenOpen)
    }

    /** `kiosk-host` › System screen from the device menu › Java crash under a system screen. */
    @Test
    fun `A Java crash under a system screen counts, and the record is still carried`() {
        start(elapsedMs = 100_000)
        journal.systemScreenOpened("android settings", 100_000)
        crash()
        val state = start(elapsedMs = 200_000)
        assertEquals(RunEnd.CRASHED, state.previousEnd)
        assertEquals("java.lang.IllegalStateException", state.previousCrash)
        assertEquals(1, state.recentEarlyEnds)
        assertTrue(state.systemScreenOpen)
        assertFalse(state.systemScreenEndsApp, "a Java crash teaches nothing about the firmware")
        assertEquals(RunEnd.PLANNED, start(elapsedMs = 260_000).previousEnd, "the carried record covers the next end")
    }

    @Test
    fun `The records of a system screen and of an update are withdrawn apart`() {
        start(elapsedMs = 100_000)
        journal.plannedExit("update", 100_000, validForMs = 15 * 60_000)
        journal.systemScreenOpened("device administration", 100_000)
        journal.cancelSystemScreen()
        crash()
        assertEquals(RunEnd.PLANNED, start(elapsedMs = 200_000).previousEnd, "the update's record still counts before a Java crash")

        journal.systemScreenOpened("android settings", 200_000)
        journal.plannedExit("update", 200_000, validForMs = 15 * 60_000)
        journal.cancelPlannedExit()
        assertEquals(RunEnd.PLANNED, start(elapsedMs = 400_000).previousEnd, "cancelPlannedExit leaves the system screen's record")
    }

    @Test
    fun `A device restart, a new installation and the end of the validity drop the record`() {
        start(elapsedMs = 100_000)
        journal.systemScreenOpened("android settings", 100_000)
        assertFalse(start(elapsedMs = 5_000, bootId = "boot-2").systemScreenOpen, "not after a device restart")

        journal.systemScreenOpened("android settings", 5_000)
        val replaced = start(elapsedMs = 300_000, bootId = "boot-2", installId = "101:1")
        assertEquals(RunEnd.REPLACED, replaced.previousEnd)
        assertFalse(replaced.systemScreenOpen, "not after a new installation")

        journal.systemScreenOpened("android settings", 300_000)
        val late = start(elapsedMs = 300_000 + 11 * 60_000, bootId = "boot-2", installId = "101:1")
        assertEquals(RunEnd.DIED, late.previousEnd, "11 minutes later without a start in between")
        assertFalse(late.systemScreenOpen)
        assertFalse(journal.systemScreenOpen(300_000 + 11 * 60_000))
    }

    /** `kiosk-host` › Android settings hidden where the firmware ends the app › Firmware not recognized. */
    @Test
    fun `A start within a minute of the record teaches that the firmware ends the app`() {
        start(elapsedMs = 100_000)
        journal.systemScreenOpened("android settings", 100_000)
        assertFalse(start(elapsedMs = 190_000).systemScreenEndsApp, "not at 90 s")

        // The record was renewed at 190 s; the next end comes 30 s after that.
        val learned = start(elapsedMs = 220_000)
        assertEquals(RunEnd.PLANNED, learned.previousEnd)
        assertTrue(learned.systemScreenEndsApp, "at 30 s")

        journal.cancelSystemScreen()
        assertTrue(start(elapsedMs = 5_000, bootId = "boot-2").systemScreenEndsApp, "kept across a device restart")
        assertTrue(start(elapsedMs = 6_000, bootId = "boot-2").systemScreenEndsApp, "and further starts")
        assertFalse(start(elapsedMs = 7_000, bootId = "boot-2", installId = "101:1").systemScreenEndsApp, "cleared by a new installation")
    }

    @Test
    fun `A planned restart within the minute teaches nothing about the firmware`() {
        start(elapsedMs = 100_000)
        journal.systemScreenOpened("android settings", 100_000)
        // "Start normally" or the probe restarts the app 20 s later, with the settings still in front.
        journal.plannedExit("restart: menu", 120_000, validForMs = 60_000)
        val state = start(elapsedMs = 125_000)
        assertEquals(RunEnd.PLANNED, state.previousEnd)
        assertTrue(state.systemScreenOpen, "the record goes on")
        assertFalse(state.systemScreenEndsApp)
    }

    @Test
    fun `A runs json written by 0_10_0 reads without loss`() {
        file.parentFile.mkdirs()
        file.writeText(
            """{"bootId":"boot-1","installId":"100:1700000000000","lastElapsedMs":60000,"stable":false,""" +
                """"planned":{"reason":"update","atElapsedMs":60000,"validForMs":900000},"earlyEnds":2,"probeIndex":0}""",
        )
        val planned = start(elapsedMs = 120_000)
        assertEquals(RunEnd.PLANNED, planned.previousEnd)
        assertFalse(planned.systemScreenOpen)
        assertFalse(planned.systemScreenEndsApp)

        file.writeText(
            """{"bootId":"boot-1","installId":"100:1700000000000","lastElapsedMs":60000,"stable":false,""" +
                """"javaCrash":"java.lang.IllegalStateException","earlyEnds":2}""",
        )
        val crashed = start(elapsedMs = 120_000)
        assertEquals(RunEnd.CRASHED, crashed.previousEnd)
        assertEquals(3, crashed.recentEarlyEnds, "the count of early ends goes on")
        assertEquals(HealthMode.SAFE, crashed.mode)
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
        blocked.systemScreenOpened("x", 0)
        blocked.cancelSystemScreen()
        blocked.requestNormalStart()
    }
}
