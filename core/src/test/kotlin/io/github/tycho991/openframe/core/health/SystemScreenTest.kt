package io.github.tycho991.openframe.core.health

import io.github.tycho991.openframe.core.testing.FakeDisplayPower
import io.github.tycho991.openframe.core.testing.FakePlatform
import io.github.tycho991.openframe.core.testing.TestFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Spec `kiosk-host`: Android settings hidden where the firmware ends the app, System screen from the device menu,
 * Display while a system screen is open; the core's side of the device menu's system screens.
 */
class SystemScreenTest {
    @TempDir
    lateinit var dir: File

    private fun frame(
        platform: FakePlatform = FakePlatform(),
        learned: Boolean = false,
        journal: RunJournal = RunJournal(null),
        power: FakeDisplayPower = FakeDisplayPower(),
    ) = TestFrame(
        File(dir, "frame"),
        platform = platform,
        displayPower = power,
        health = Health(journal, HealthState(systemScreenEndsApp = learned)) { 60_000 },
    )

    /** `kiosk-host` › Android settings hidden where the firmware ends the app: each combination of the two signals. */
    @Test
    fun `Android settings are offered only without either signal`() {
        for (firmware in listOf(false, true)) {
            for (learned in listOf(false, true)) {
                frame(FakePlatform(otherActivityEndsApp = firmware), learned).use {
                    assertEquals(!firmware && !learned, it.core.androidSettingsOffered, "firmware $firmware, learned $learned")
                }
            }
        }
    }

    @Test
    fun `Opening a system screen records it and holds the display, closing withdraws both`() {
        val journal = RunJournal(null)
        val power = FakeDisplayPower()
        frame(journal = journal, power = power).use {
            it.core.systemScreenOpened("android settings")
            waitFor { power.keptOn }
            assertTrue(it.core.health.state.value.systemScreenOpen)
            assertTrue(journal.systemScreenOpen(60_000))
            val timeout = power.keepOnTimeouts.single()
            assertTrue(
                timeout in RunJournal.SYSTEM_SCREEN_VALID_MS - 5_000..RunJournal.SYSTEM_SCREEN_VALID_MS,
                "held for 10 minutes: $timeout",
            )

            it.core.systemScreenClosed()
            waitFor { !power.keptOn }
            assertFalse(it.core.health.state.value.systemScreenOpen)
            assertFalse(journal.systemScreenOpen(60_000))
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "condition not met within 5 s" }
            Thread.sleep(20)
        }
    }
}
