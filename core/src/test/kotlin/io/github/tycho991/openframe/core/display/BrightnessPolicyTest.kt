package io.github.tycho991.openframe.core.display

import io.github.tycho991.openframe.core.platform.RawBrightness
import io.github.tycho991.openframe.core.settings.BrightnessMode
import io.github.tycho991.openframe.core.settings.BrightnessSettings
import io.github.tycho991.openframe.core.settings.DisplayScheduleSettings
import io.github.tycho991.openframe.core.settings.DisplayWindow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

/** Design decision 4: the order of precedence of the brightness, with `dimmed` and `edge`. 2026-10-21 is a Wednesday. */
class BrightnessPolicyTest {
    private val schedule = DisplayScheduleSettings(enabled = true, weekdays = DisplayWindow("07:00", "22:00", "20:30"), dimLevel = 20)
    private val manual = BrightnessSettings(BrightnessMode.MANUAL, 80)
    private val day = Instant.parse("2026-10-21T15:00:00Z")
    private val evening = Instant.parse("2026-10-21T21:00:00Z")
    private val night = Instant.parse("2026-10-21T23:00:00Z")
    private val dimStart = Instant.parse("2026-10-21T20:30:00Z")
    private val nightStart = Instant.parse("2026-10-21T22:00:00Z")

    private fun decide(
        now: Instant,
        brightness: BrightnessSettings = manual,
        schedule: DisplayScheduleSettings = this.schedule,
        available: Boolean = true,
        override: BrightnessOverride? = null,
        snapshot: RawBrightness? = null,
    ) = BrightnessPolicy.decide(BrightnessInputs(brightness, schedule, available, override, snapshot), now, ZoneOffset.UTC)

    @Test
    fun `Not available decides nothing, whatever else is set`() {
        val d = decide(evening, available = false, override = BrightnessOverride(50, dimStart, 1), snapshot = RawBrightness(10, true))
        assertEquals(BrightnessDecision(null, null, null), d)
        assertFalse(d.dimmed)
    }

    @Test
    fun `An override in its dim period decides`() {
        val d = decide(evening, override = BrightnessOverride(50, dimStart, 1))
        assertEquals(BrightnessTarget.Level(50), d.target)
        assertTrue(d.dimmed)
        assertNotEquals(decide(evening, override = BrightnessOverride(50, dimStart, 2)).edge, d.edge)
    }

    @Test
    fun `An override ends with its dim period`() {
        assertEquals(BrightnessTarget.Level(20), decide(night, override = BrightnessOverride(50, dimStart, 1)).target)
        assertEquals(
            BrightnessTarget.Level(80),
            decide(Instant.parse("2026-10-22T07:00:00Z"), override = BrightnessOverride(50, nightStart, 1)).target,
        )
    }

    @Test
    fun `A dim period dims`() {
        val d = decide(evening)
        assertEquals(BrightnessTarget.Level(20), d.target)
        assertEquals(dimStart, d.dimStart)
        assertEquals(d.edge, decide(Instant.parse("2026-10-21T21:59:00Z")).edge)
    }

    @Test
    fun `The night after a dim period stays dim, as a new stretch`() {
        val d = decide(night)
        assertEquals(BrightnessTarget.Level(20), d.target)
        assertEquals(nightStart, d.dimStart)
        assertNotEquals(decide(evening).edge, d.edge)
    }

    @Test
    fun `Another dim level is a new edge`() {
        assertNotEquals(decide(evening).edge, decide(evening, schedule = schedule.copy(dimLevel = 30)).edge)
    }

    @Test
    fun `Stored modes outside a dim period`() {
        assertEquals(BrightnessTarget.Level(80), decide(day).target)
        assertEquals(BrightnessTarget.Auto, decide(day, brightness = BrightnessSettings(BrightnessMode.AUTO, 80)).target)
        assertNull(decide(day, brightness = BrightnessSettings()).target)
        assertFalse(decide(day).dimmed)
        assertNotEquals(decide(day).edge, decide(day, brightness = manual.copy(level = 60)).edge)
    }

    @Test
    fun `Mode system restores the snapshot`() {
        val raw = RawBrightness(200, auto = true)
        val d = decide(day, brightness = BrightnessSettings(), snapshot = raw)
        assertEquals(BrightnessTarget.Restore(raw), d.target)
        // Restoring deletes the snapshot; that is no new edge, so nothing is written again.
        assertEquals(d.edge, decide(day, brightness = BrightnessSettings()).edge)
    }

    @Test
    fun `Schedule off or its clock not set does not dim`() {
        assertEquals(BrightnessTarget.Level(80), decide(evening, schedule = schedule.copy(enabled = false)).target)
        assertFalse(decide(Instant.parse("2021-10-20T21:00:00Z")).dimmed)
    }
}
