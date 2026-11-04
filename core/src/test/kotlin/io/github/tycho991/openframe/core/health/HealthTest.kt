package io.github.tycho991.openframe.core.health

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Design decision 9 of `crash-resilience`: the interface app-auto-update builds on. */
class HealthTest {
    @TempDir
    lateinit var dir: File

    private var uptime = 60_000L

    /** A new process on the same journal file, as the app starts after its end. */
    private fun run(installId: String = "100:1"): Health {
        val journal = RunJournal(File(dir, "runs.json"))
        return Health(journal, journal.onProcessStart(0, uptime, "boot", installId)) { uptime }
    }

    @Test
    fun `A planned exit before the end makes the next start a planned one, an expired one an unexplained one`() {
        val before = run()
        // Before committing the installation, valid beyond the 10 minutes of the confirmation dialog.
        before.plannedExit("update", validForMs = 15 * 60_000)
        uptime += 11 * 60_000
        val after = run()
        assertEquals(RunEnd.PLANNED, after.state.value.previousEnd)
        assertEquals(0, after.state.value.recentEarlyEnds)

        after.plannedExit("update", validForMs = 15 * 60_000)
        uptime += 16 * 60_000
        val expired = run()
        assertEquals(RunEnd.DIED, expired.state.value.previousEnd)
        assertEquals(1, expired.state.value.recentEarlyEnds)

        expired.plannedExit("update", validForMs = 15 * 60_000)
        expired.cancelPlannedExit()
        uptime += 1_000
        assertEquals(RunEnd.DIED, run().state.value.previousEnd, "cancelled on cancel, failure or abandon")
    }

    @Test
    fun `A new installation is always the planned end replaced and ends safe mode`() {
        repeat(4) { run() }
        assertEquals(HealthMode.SAFE, run().state.value.mode)
        val updated = run(installId = "101:2")
        assertEquals(RunEnd.REPLACED, updated.state.value.previousEnd)
        assertEquals(HealthMode.NORMAL, updated.state.value.mode)
        assertEquals(null, updated.state.value.safeMode)
    }
}
