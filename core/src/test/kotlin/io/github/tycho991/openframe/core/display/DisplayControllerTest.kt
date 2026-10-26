@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.tycho991.openframe.core.display

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.settings.DisplayScheduleSettings
import io.github.tycho991.openframe.core.settings.Settings
import io.github.tycho991.openframe.core.testing.FakeDisplayPower
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Spec `display-schedule`: Switching on time, Waking during an off period, Grace at start and on changes, Manual
 * switch, Losing device administration. Time is the test scheduler's and the [TestClock], moved together.
 * 2026-10-21 is a Wednesday; the schedule has the default windows (weekdays 07:00 to 22:00).
 */
class DisplayControllerTest {
    private val scope = TestScope()
    private val clock = TestClock(Instant.parse("2026-10-21T15:00:00Z"))
    private val power = FakeDisplayPower()
    private val settings = MutableStateFlow(Settings(name = "Frame", displaySchedule = DisplayScheduleSettings(enabled = true)))
    private val controller = DisplayController(scope, power, settings, clock, AppLog(clock))

    @AfterEach
    fun stop() = scope.cancel()

    private fun start() {
        controller.start()
        scope.runCurrent()
    }

    /** Lets [duration] pass on the wall clock, the monotonic time and the scheduler. */
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

    /** Lets the time pass until [local] (UTC) on the wall clock. */
    private fun until(local: String) = advance(Duration.between(clock.now(), Instant.parse("$local:00Z")))

    private fun minutes(n: Long) = advance(Duration.ofMinutes(n))

    private fun schedule(change: (DisplayScheduleSettings) -> DisplayScheduleSettings) {
        settings.value = settings.value.copy(displaySchedule = change(settings.value.displaySchedule))
        scope.runCurrent()
    }

    private fun manual(on: Boolean): DisplayState {
        val r = scope.async { controller.setManual(on) }
        scope.runCurrent()
        return r.getCompleted()
    }

    private val state get() = controller.state.value

    // Switching on time

    @Test
    fun `Night without touch`() {
        start()
        until("2026-10-21T21:59")
        assertTrue(power.screenOn)
        assertEquals(DisplayState(true, true, DisplayCause.SCHEDULE, Instant.parse("2026-10-21T22:00:00Z")), state)
        minutes(1)
        assertFalse(power.screenOn, "off within a minute of the off time")
        assertEquals(DisplayState(true, false, DisplayCause.SCHEDULE, Instant.parse("2026-10-22T07:00:00Z")), state)
        until("2026-10-22T06:59")
        assertFalse(power.screenOn)
        minutes(1)
        assertTrue(power.screenOn, "on within a minute of the on time")
        assertEquals(listOf("off", "on"), power.calls)
    }

    @Test
    fun `Clock corrected after boot`() {
        // The Frameo starts with its clock in 2021, a Friday at noon.
        clock.instant = Instant.parse("2021-01-01T12:00:00Z")
        start()
        assertTrue(power.screenOn)
        clock.instant = Instant.parse("2026-10-21T23:00:00Z")
        advance(Duration.ofSeconds(30))
        assertFalse(power.screenOn, "follows the corrected time within a minute")
        assertEquals(listOf("off"), power.calls)
    }

    @Test
    fun `A change of the time zone takes effect within a minute`() {
        clock.instant = Instant.parse("2026-10-21T19:30:00Z")
        start()
        assertTrue(power.screenOn)
        // 19:30 UTC is 04:30 the next morning in Tokyo.
        clock.zoneId = ZoneId.of("Asia/Tokyo")
        advance(Duration.ofSeconds(30))
        assertFalse(power.screenOn)
    }

    @Test
    fun `A clock jump of five years forward at night neither ends a waking period nor skips the switch`() {
        clock.instant = Instant.parse("2026-10-21T23:00:00Z")
        start()
        minutes(6)
        assertFalse(power.screenOn)
        power.powerButton()
        scope.runCurrent()
        // Tuesday night, five years later.
        clock.instant = Instant.parse("2031-10-21T23:01:00Z")
        minutes(4)
        assertTrue(power.screenOn, "the waking period lasts its five minutes on the monotonic clock")
        assertEquals(DisplayCause.WAKE, state.cause)
        minutes(2)
        assertFalse(power.screenOn)
        until("2031-10-22T07:00")
        advance(Duration.ofSeconds(30))
        assertTrue(power.screenOn, "the morning switch comes at the new date")
    }

    @Test
    fun `A clock jump forward from day into night switches off`() {
        start()
        clock.instant = Instant.parse("2031-10-21T23:00:00Z")
        advance(Duration.ofSeconds(30))
        assertFalse(power.screenOn)
    }

    // Waking during an off period

    @Test
    fun `Power button at night`() {
        clock.instant = Instant.parse("2026-10-21T22:30:00Z")
        start()
        minutes(6)
        assertFalse(power.screenOn)
        power.powerButton()
        scope.runCurrent()
        assertEquals(DisplayCause.WAKE, state.cause)
        assertTrue(state.on)
        assertEquals(clock.now().plus(Duration.ofMinutes(5)), state.nextChange)
        minutes(4)
        assertTrue(power.screenOn)
        advance(Duration.ofSeconds(90))
        assertFalse(power.screenOn, "off again five minutes after waking")
        assertEquals(DisplayCause.SCHEDULE, state.cause)
        assertEquals(listOf("off", "off"), power.calls)
    }

    @Test
    fun `Touches extend the time`() {
        clock.instant = Instant.parse("2026-10-21T23:00:00Z")
        start()
        minutes(6)
        power.powerButton()
        scope.runCurrent()
        repeat(20) {
            advance(Duration.ofSeconds(30))
            controller.userActivity()
            scope.runCurrent()
        }
        assertTrue(power.screenOn, "on while someone swipes for ten minutes")
        minutes(4)
        assertTrue(power.screenOn)
        advance(Duration.ofSeconds(90))
        assertFalse(power.screenOn, "off five minutes after the last swipe")
    }

    @Test
    fun `Touches are reported at most once a second`() {
        clock.instant = Instant.parse("2026-10-21T23:00:00Z")
        start()
        minutes(6)
        power.powerButton()
        scope.runCurrent()
        minutes(4)
        controller.userActivity()
        scope.runCurrent()
        val end = state.nextChange
        assertEquals(clock.now().plus(Duration.ofMinutes(5)), end)
        // Another touch within the same second does not move the end.
        clock.advance(Duration.ofMillis(999))
        controller.userActivity()
        scope.runCurrent()
        advance(Duration.ofSeconds(30))
        assertEquals(end, state.nextChange)
        clock.advance(Duration.ofMillis(1))
        controller.userActivity()
        scope.runCurrent()
        assertEquals(clock.now().plus(Duration.ofMinutes(5)), state.nextChange)
    }

    @Test
    fun `Waking that runs into the morning keeps the display on`() {
        clock.instant = Instant.parse("2026-10-22T06:00:00Z")
        start()
        minutes(6)
        until("2026-10-22T06:58")
        power.powerButton()
        scope.runCurrent()
        minutes(10)
        assertTrue(power.screenOn)
        assertEquals(DisplayCause.SCHEDULE, state.cause)
        assertEquals(Instant.parse("2026-10-22T22:00:00Z"), state.nextChange)
    }

    // Grace at start and on changes

    @Test
    fun `Schedule switched on in the evening`() {
        schedule { it.copy(enabled = false) }
        clock.instant = Instant.parse("2026-10-21T23:00:00Z")
        start()
        assertEquals(DisplayCause.NONE, state.cause)
        schedule { it.copy(enabled = true) }
        assertTrue(power.screenOn, "not off at once")
        assertEquals(DisplayCause.WAKE, state.cause)
        minutes(3)
        controller.userActivity()
        scope.runCurrent()
        minutes(4)
        assertTrue(power.screenOn, "five minutes after the last touch, not after switching on")
        minutes(2)
        assertFalse(power.screenOn)
    }

    @Test
    fun `Schedule switched off at night`() {
        clock.instant = Instant.parse("2026-10-21T23:00:00Z")
        start()
        minutes(6)
        assertFalse(power.screenOn)
        schedule { it.copy(enabled = false) }
        assertTrue(power.screenOn, "on within a minute")
        assertEquals(DisplayCause.NONE, state.cause)
        assertEquals(listOf("off", "on"), power.calls)
    }

    @Test
    fun `Schedule changed so that it calls for on`() {
        clock.instant = Instant.parse("2026-10-21T23:00:00Z")
        start()
        minutes(6)
        schedule { it.copy(weekdays = it.weekdays.copy(off = "23:30")) }
        assertTrue(power.screenOn)
        assertEquals(DisplayCause.SCHEDULE, state.cause)
    }

    @Test
    fun `Schedule changed while on calls for off after the grace`() {
        start()
        schedule { it.copy(weekdays = it.weekdays.copy(off = "14:00")) }
        assertTrue(power.screenOn)
        assertEquals(DisplayCause.WAKE, state.cause)
        minutes(6)
        assertFalse(power.screenOn)
    }

    @Test
    fun `Reboot at night`() {
        clock.instant = Instant.parse("2026-10-21T23:00:00Z")
        start()
        assertTrue(power.screenOn, "the slideshow appears")
        assertEquals(DisplayCause.WAKE, state.cause)
        minutes(4)
        assertTrue(power.screenOn)
        minutes(2)
        assertFalse(power.screenOn)
    }

    @Test
    fun `A start at night with the display already off does not switch it on`() {
        clock.instant = Instant.parse("2026-10-21T23:00:00Z")
        power.screen(false)
        start()
        minutes(10)
        assertFalse(power.screenOn)
        assertEquals(emptyList<String>(), power.calls)
    }

    // Manual switch

    @Test
    fun `Off by day`() {
        start()
        val s = manual(false)
        assertEquals(DisplayState(true, false, DisplayCause.MANUAL, Instant.parse("2026-10-22T07:00:00Z")), s)
        until("2026-10-22T06:59")
        assertFalse(power.screenOn, "stays off through the night")
        minutes(1)
        assertTrue(power.screenOn)
        assertEquals(DisplayCause.SCHEDULE, state.cause)
        assertEquals(listOf("off", "on"), power.calls)
    }

    @Test
    fun `On at night`() {
        clock.instant = Instant.parse("2026-10-21T23:00:00Z")
        start()
        minutes(10)
        assertFalse(power.screenOn)
        val s = manual(true)
        assertEquals(DisplayState(true, true, DisplayCause.MANUAL, Instant.parse("2026-10-22T22:00:00Z")), s)
        until("2026-10-22T21:59")
        assertTrue(power.screenOn, "stays on through the night and the day")
        minutes(1)
        assertFalse(power.screenOn, "off at the off time of the following evening")
    }

    @Test
    fun `Off without schedule, then the power button`() {
        schedule { it.copy(enabled = false) }
        start()
        val s = manual(false)
        assertEquals(DisplayState(true, false, DisplayCause.MANUAL, null), s)
        minutes(60)
        assertFalse(power.screenOn, "holds without an end")
        power.powerButton()
        scope.runCurrent()
        minutes(60)
        assertTrue(power.screenOn, "the display stays on")
        assertEquals(DisplayCause.NONE, state.cause)
        assertEquals(listOf("off"), power.calls)
    }

    @Test
    fun `On without schedule, then switched off by hand`() {
        schedule { it.copy(enabled = false) }
        start()
        manual(true)
        power.powerButton()
        scope.runCurrent()
        minutes(60)
        assertFalse(power.screenOn, "the frame does not fight the user")
    }

    @Test
    fun `A foreign SCREEN_OFF by day is not undone`() {
        start()
        // Another app's schedule or the power button.
        power.screen(false)
        scope.runCurrent()
        assertEquals(DisplayState(true, false, DisplayCause.MANUAL, Instant.parse("2026-10-22T07:00:00Z")), state)
        until("2026-10-22T06:59")
        assertFalse(power.screenOn)
        assertEquals(emptyList<String>(), power.calls)
        minutes(1)
        assertTrue(power.screenOn, "on at the next on time")
    }

    @Test
    fun `A SCREEN_ON sent late while the display is off is not waking`() {
        start()
        until("2026-10-21T21:59")
        minutes(1)
        assertFalse(power.screenOn)
        // The first switch to off after boot: Android announces it as SCREEN_ON, then SCREEN_OFF.
        power.staleEvent(true)
        scope.runCurrent()
        minutes(10)
        assertFalse(power.screenOn)
        assertEquals(DisplayCause.SCHEDULE, state.cause)
        assertEquals(listOf("off"), power.calls)
    }

    // Losing device administration

    @Test
    fun `Deactivated at night`() {
        clock.instant = Instant.parse("2026-10-21T23:00:00Z")
        start()
        minutes(6)
        assertFalse(power.screenOn)
        power.setAvailable(false)
        scope.runCurrent()
        assertTrue(power.screenOn, "on within a minute")
        assertEquals(DisplayState(false, true, DisplayCause.NONE, null), state)
        minutes(60)
        assertTrue(power.screenOn, "and stays on")
        assertTrue(settings.value.displaySchedule.enabled)
        assertEquals(listOf("off", "on"), power.calls)
    }

    @Test
    fun `A display off by hand stays off when device administration ends`() {
        clock.instant = Instant.parse("2026-10-21T23:00:00Z")
        start()
        power.powerButton()
        scope.runCurrent()
        power.setAvailable(false)
        scope.runCurrent()
        assertFalse(power.screenOn)
    }

    @Test
    fun `Activated again, the stored schedule applies after the grace`() {
        clock.instant = Instant.parse("2026-10-21T23:00:00Z")
        power.setAvailable(false)
        start()
        minutes(10)
        assertTrue(power.screenOn)
        power.setAvailable(true)
        scope.runCurrent()
        assertTrue(power.screenOn)
        assertEquals(DisplayCause.WAKE, state.cause)
        minutes(6)
        assertFalse(power.screenOn)
    }

    @Test
    fun `A manual switch is ignored while not available`() {
        power.setAvailable(false)
        start()
        val s = manual(false)
        assertTrue(s.on)
        assertEquals(emptyList<String>(), power.calls)
    }
}
