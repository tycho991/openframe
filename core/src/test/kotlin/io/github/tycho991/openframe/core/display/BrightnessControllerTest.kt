@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.tycho991.openframe.core.display

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.RawBrightness
import io.github.tycho991.openframe.core.settings.BrightnessMode
import io.github.tycho991.openframe.core.settings.BrightnessSettings
import io.github.tycho991.openframe.core.settings.DisplayScheduleSettings
import io.github.tycho991.openframe.core.settings.DisplayWindow
import io.github.tycho991.openframe.core.settings.Settings
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.FakeDisplayPower
import io.github.tycho991.openframe.core.testing.FakeScreenBrightness
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Spec `display-brightness` (Modes of the brightness, Losing the permission, Brightness now, Changes on the device,
 * Brightness in the display state) and the brightness of spec `display-schedule` (Dim periods, Dimmed during the night,
 * Brightness after a dim period, Dimming changed during a dim period). 2026-10-21 is a Wednesday; the schedule has the
 * default windows (weekdays 07:00 to 22:00) and dims on weekdays from 20:30 to 20 %.
 */
class BrightnessControllerTest {
    @TempDir
    lateinit var dir: File

    private val scope = TestScope()
    private val clock = TestClock(Instant.parse("2026-10-21T12:00:00Z"))
    private val power = FakeDisplayPower()
    private val screen = FakeScreenBrightness(value = RawBrightness(128, auto = false))
    private val dimmed = DisplayScheduleSettings(enabled = true, weekdays = DisplayWindow("07:00", "22:00", "20:30"))
    private val settings =
        MutableStateFlow(Settings(name = "Frame", displaySchedule = dimmed, brightness = BrightnessSettings(BrightnessMode.MANUAL, 40)))
    private val log = AppLog(clock)
    private val snapshots get() = JsonStore(File(dir, "brightness-restore.json"), StoredBrightnessSnapshot.serializer(), scope, log)
    private lateinit var controller: DisplayController

    @BeforeEach
    fun create() {
        controller = controller()
    }

    private fun controller() =
        DisplayController(scope, power, settings, clock, log, brightness = screen, snapshots = snapshots, storeBrightness = { level ->
            settings.value = settings.value.copy(brightness = BrightnessSettings(BrightnessMode.MANUAL, level))
        })

    @AfterEach
    fun stop() = scope.cancel()

    private fun start() {
        controller.start()
        scope.runCurrent()
    }

    private fun advance(duration: Duration) {
        var left = duration.toMillis()
        while (left > 0) {
            val step = minOf(left, 10_000L)
            clock.advance(Duration.ofMillis(step))
            scope.advanceTimeBy(step)
            scope.runCurrent()
            left -= step
        }
    }

    private fun until(local: String) = advance(Duration.between(clock.now(), Instant.parse("$local:00Z")))

    private fun minutes(n: Long) = advance(Duration.ofMinutes(n))

    private fun change(block: (Settings) -> Settings) {
        settings.value = block(settings.value)
        scope.runCurrent()
    }

    private fun set(
        on: Boolean?,
        level: Int?,
    ): DisplayState {
        val r = scope.async { controller.set(on, level) }
        scope.runCurrent()
        return r.getCompleted()
    }

    private fun raw(level: Int) = RawBrightness(BrightnessScale.toRaw(level), auto = false)

    private val brightness get() = controller.state.value.brightness

    // Modes of the brightness

    @Test
    fun `Manual level`() {
        change { it.copy(brightness = BrightnessSettings(BrightnessMode.SYSTEM, 80)) }
        start()
        assertEquals(emptyList<RawBrightness>(), screen.writes)
        change { it.copy(brightness = BrightnessSettings(BrightnessMode.MANUAL, 40)) }
        assertEquals(listOf(RawBrightness(102, auto = false)), screen.writes, "at once, without waiting for a tick")
    }

    @Test
    fun `Back to system`() {
        start()
        change { it.copy(brightness = BrightnessSettings(BrightnessMode.SYSTEM, 40)) }
        minutes(5)
        assertEquals(listOf(raw(40)), screen.writes)
        assertEquals(raw(40), screen.current)
    }

    @Test
    fun `Automatic keeps the manual value`() {
        start()
        change { it.copy(brightness = BrightnessSettings(BrightnessMode.AUTO, 40)) }
        assertEquals(RawBrightness(102, auto = true), screen.current)
        assertTrue(brightness.auto)
    }

    // Losing the permission

    @Test
    fun `Withdrawn and granted again`() {
        start()
        screen.available = false
        minutes(1)
        screen.onDevice(RawBrightness(255, auto = false))
        minutes(5)
        assertEquals(listOf(raw(40)), screen.writes)
        assertFalse(brightness.available)
        screen.available = true
        minutes(1)
        assertEquals(raw(40), screen.current)
        assertEquals(BrightnessSettings(BrightnessMode.MANUAL, 40), settings.value.brightness)
    }

    @Test
    fun `Granted, seen at once on a recheck`() {
        screen.available = false
        start()
        screen.available = true
        controller.recheck()
        scope.runCurrent()
        assertEquals(listOf(raw(40)), screen.writes)
    }

    // Brightness now

    @Test
    fun `By day`() {
        change { it.copy(brightness = BrightnessSettings(BrightnessMode.AUTO, 80)) }
        start()
        val s = set(null, 60)
        assertEquals(raw(60), screen.current)
        assertEquals(60, s.brightness.level)
        assertEquals(BrightnessSettings(BrightnessMode.MANUAL, 60), settings.value.brightness)
    }

    @Test
    fun `The same level as stored undoes a change on the device`() {
        start()
        screen.onDevice(RawBrightness(230, auto = false))
        set(null, 40)
        assertEquals(raw(40), screen.current)
    }

    @Test
    fun `During a dim period`() {
        start()
        until("2026-10-21T21:30")
        set(null, 50)
        assertEquals(raw(50), screen.current)
        assertEquals(BrightnessSettings(BrightnessMode.MANUAL, 40), settings.value.brightness)
        until("2026-10-21T21:59")
        assertEquals(raw(50), screen.current)
        until("2026-10-22T07:01")
        assertEquals(raw(40), screen.current, "the next morning the stored level applies")
    }

    @Test
    fun `During the night after a dim period`() {
        start()
        until("2026-10-21T23:00")
        power.powerButton()
        scope.runCurrent()
        set(null, 70)
        assertEquals(raw(70), screen.current)
        until("2026-10-22T07:01")
        assertEquals(raw(40), screen.current)
    }

    @Test
    fun `On and brightness together`() {
        start()
        set(false, null)
        assertFalse(power.screenOn)
        val s = set(true, 30)
        assertTrue(power.screenOn)
        assertEquals(raw(30), screen.current)
        assertTrue(s.on)
        assertEquals(30, s.brightness.level)
    }

    // Changes on the device

    @Test
    fun `Quick settings by day`() {
        start()
        screen.onDevice(RawBrightness(230, auto = false))
        advance(Duration.ofSeconds(30))
        assertEquals(90, brightness.level)
        until("2026-10-21T20:00")
        assertEquals(listOf(raw(40)), screen.writes)
        assertEquals(RawBrightness(230, auto = false), screen.current)
    }

    @Test
    fun `Next dim period`() {
        start()
        screen.onDevice(RawBrightness(230, auto = false))
        until("2026-10-21T20:31")
        assertEquals(raw(20), screen.current)
    }

    @Test
    fun `A change during a dim period holds until it ends`() {
        start()
        until("2026-10-21T21:00")
        screen.onDevice(RawBrightness(230, auto = false))
        until("2026-10-21T21:59")
        assertEquals(RawBrightness(230, auto = false), screen.current)
    }

    @Test
    fun `A setting that is not the brightness changes nothing`() {
        start()
        screen.onDevice(RawBrightness(230, auto = false))
        change { it.copy(intervalSeconds = 60) }
        minutes(1)
        assertEquals(RawBrightness(230, auto = false), screen.current)
    }

    // Brightness in the display state

    @Test
    fun `State in the evening`() {
        start()
        until("2026-10-21T21:30")
        assertEquals(BrightnessState(available = true, autoSupported = true, level = 20, auto = false, dimmed = true), brightness)
    }

    @Test
    fun `Without permission`() {
        screen.available = false
        screen.onDevice(RawBrightness(128, auto = false))
        start()
        assertEquals(BrightnessState(available = false, autoSupported = true, level = 50, auto = false, dimmed = false), brightness)
    }

    // Dim periods

    @Test
    fun `Weekday evening`() {
        change { it.copy(displaySchedule = it.displaySchedule.copy(dimLevel = 20)) }
        start()
        until("2026-10-21T20:29")
        assertEquals(raw(40), screen.current)
        minutes(1)
        assertEquals(raw(20), screen.current, "within a minute of the dim time")
        until("2026-10-21T22:01")
        assertFalse(power.screenOn)
    }

    @Test
    fun `Schedule off`() {
        change { it.copy(displaySchedule = it.displaySchedule.copy(enabled = false)) }
        start()
        until("2026-10-21T21:00")
        assertEquals(listOf(raw(40)), screen.writes)
        assertFalse(brightness.dimmed)
    }

    @Test
    fun `Dim time without permission`() {
        screen.available = false
        start()
        until("2026-10-21T21:00")
        assertEquals(emptyList<RawBrightness>(), screen.writes)
        until("2026-10-21T22:01")
        assertFalse(power.screenOn, "the schedule still switches the display")
    }

    // Dimmed during the night

    @Test
    fun `Power button at night`() {
        start()
        until("2026-10-21T23:00")
        power.powerButton()
        scope.runCurrent()
        assertTrue(power.screenOn)
        assertEquals(raw(20), screen.current)
        assertTrue(brightness.dimmed)
    }

    // Brightness after a dim period

    @Test
    fun `Morning in mode manual`() {
        change { it.copy(brightness = BrightnessSettings(BrightnessMode.MANUAL, 80)) }
        start()
        until("2026-10-22T07:01")
        assertTrue(power.screenOn)
        assertEquals(raw(80), screen.current)
    }

    @Test
    fun `Morning in mode system`() {
        change { it.copy(brightness = BrightnessSettings()) }
        screen.onDevice(RawBrightness(200, auto = true))
        start()
        until("2026-10-21T21:00")
        assertEquals(RawBrightness(BrightnessScale.toRaw(20), auto = false), screen.current)
        // The device restarts during the night.
        until("2026-10-22T02:00")
        runBlocking { snapshots.flush() }
        scope.cancel()
        val restarted = TestScope()
        controller = DisplayController(restarted, power, settings, clock, log, brightness = screen, snapshots = snapshots)
        controller.start()
        restarted.runCurrent()
        assertEquals(raw(20), screen.current, "still dim after the restart")
        while (clock.now() < Instant.parse("2026-10-22T07:01:00Z")) {
            clock.advance(Duration.ofSeconds(10))
            restarted.advanceTimeBy(10_000)
            restarted.runCurrent()
        }
        assertEquals(RawBrightness(200, auto = true), screen.current)
        restarted.cancel()
    }

    @Test
    fun `Mode manual after a dimmed evening in mode system forgets the snapshot`() {
        change { it.copy(brightness = BrightnessSettings()) }
        screen.onDevice(RawBrightness(200, auto = true))
        start()
        until("2026-10-21T21:00")
        change { it.copy(brightness = BrightnessSettings(BrightnessMode.MANUAL, 60)) }
        until("2026-10-22T07:01")
        assertEquals(raw(60), screen.current)
        runBlocking { snapshots.flush() }
        assertEquals(null, snapshots.load()?.snapshot)
    }

    // Dimming changed during a dim period

    @Test
    fun `Dim time removed in the evening`() {
        start()
        until("2026-10-21T21:00")
        assertEquals(raw(20), screen.current)
        change { it.copy(displaySchedule = it.displaySchedule.copy(weekdays = DisplayWindow("07:00", "22:00"))) }
        minutes(1)
        assertEquals(raw(40), screen.current)
    }

    @Test
    fun `Dim level changed during a dim period`() {
        start()
        until("2026-10-21T21:00")
        change { it.copy(displaySchedule = it.displaySchedule.copy(dimLevel = 35)) }
        minutes(1)
        assertEquals(raw(35), screen.current)
    }

    @Test
    fun `Dim time moved so that the evening dims now`() {
        change { it.copy(displaySchedule = it.displaySchedule.copy(weekdays = DisplayWindow("07:00", "22:00", "21:30"))) }
        start()
        until("2026-10-21T21:00")
        assertEquals(raw(40), screen.current)
        change { it.copy(displaySchedule = it.displaySchedule.copy(weekdays = DisplayWindow("07:00", "22:00", "20:45"))) }
        minutes(1)
        assertEquals(raw(20), screen.current)
    }
}
