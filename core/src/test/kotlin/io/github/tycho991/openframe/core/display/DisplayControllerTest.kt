@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.tycho991.openframe.core.display

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.settings.DisplayScheduleSettings
import io.github.tycho991.openframe.core.settings.Settings
import io.github.tycho991.openframe.core.testing.FakeDisplayPower
import io.github.tycho991.openframe.core.testing.FakeNetworkLink
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
 * switch, Schedule only with a set clock, Asking for the network while the display is off, Dark wake for the network,
 * Losing device administration. Time is the test scheduler's and the [TestClock], moved together.
 * 2026-10-21 is a Wednesday; the schedule has the default windows (weekdays 07:00 to 22:00).
 */
class DisplayControllerTest {
    private val scope = TestScope()
    private val clock = TestClock(Instant.parse("2026-10-21T15:00:00Z"))
    private val power = FakeDisplayPower()
    private val settings = MutableStateFlow(Settings(name = "Frame", displaySchedule = DisplayScheduleSettings(enabled = true)))
    private val network = FakeNetworkLink()
    private val log = AppLog(clock)
    private val controller = DisplayController(scope, power, settings, clock, log, network)

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

    /** The log messages that start with [prefix], in order. */
    private fun logged(prefix: String) = log.recent(1000).map { it.message }.filter { it.startsWith(prefix) }

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
        // A clock that is set but wrong, a Sunday at noon; one before 2026 is not set (Schedule only with a set clock).
        clock.instant = Instant.parse("2026-03-01T12:00:00Z")
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

    // Schedule only with a set clock

    @Test
    fun `Boot without network at night`() {
        // The Frameo's clock after a boot without network: Saturday, 15 May 2021, midnight.
        clock.instant = Instant.parse("2021-05-15T00:00:00Z")
        start()
        minutes(60)
        assertTrue(power.screenOn, "the display stays on")
        assertEquals(DisplayState(true, true, DisplayCause.NONE, null), state)
        assertEquals(emptyList<String>(), power.calls)
    }

    @Test
    fun `Clock set at night`() {
        clock.instant = Instant.parse("2021-05-15T00:00:00Z")
        start()
        minutes(10)
        // Wednesday, 23:00.
        clock.instant = Instant.parse("2026-10-21T23:00:00Z")
        advance(Duration.ofSeconds(30))
        assertTrue(power.screenOn, "not off at once")
        assertEquals(DisplayCause.WAKE, state.cause)
        minutes(2)
        controller.userActivity()
        scope.runCurrent()
        minutes(4)
        assertTrue(power.screenOn, "five minutes after the last touch")
        minutes(2)
        assertFalse(power.screenOn)
        assertEquals(listOf("off"), power.calls)
    }

    @Test
    fun `Clock set by day`() {
        clock.instant = Instant.parse("2021-05-15T00:00:00Z")
        start()
        clock.instant = Instant.parse("2026-10-21T10:00:00Z")
        advance(Duration.ofSeconds(30))
        assertEquals(DisplayState(true, true, DisplayCause.SCHEDULE, Instant.parse("2026-10-21T22:00:00Z")), state)
        until("2026-10-21T21:59")
        assertTrue(power.screenOn)
        minutes(1)
        assertFalse(power.screenOn)
    }

    @Test
    fun `A clock that jumps back before the floor switches a display the frame had off back on`() {
        clock.instant = Instant.parse("2026-10-21T23:00:00Z")
        start()
        minutes(6)
        assertFalse(power.screenOn)
        clock.instant = Instant.parse("2021-05-15T00:00:00Z")
        advance(Duration.ofSeconds(30))
        assertTrue(power.screenOn, "on within a minute")
        assertEquals(DisplayCause.NONE, state.cause)
        assertEquals(listOf("off", "on"), power.calls)
    }

    @Test
    fun `The clock's state is logged once for each change`() {
        clock.instant = Instant.parse("2021-05-15T00:00:00Z")
        start()
        minutes(10)
        clock.instant = Instant.parse("2026-10-21T10:00:00Z")
        minutes(10)
        assertEquals(listOf("Clock not set (2021-05-15T00:00:00Z): schedule paused", "Clock set: schedule applies"), logged("Clock"))
    }

    // Asking for the network while the display is off

    /** Wednesday 23:00: the display goes off after the grace of the start, at 23:05. */
    private fun offAtNight() {
        clock.instant = Instant.parse("2026-10-21T23:00:00Z")
        start()
        minutes(6)
        assertFalse(power.screenOn)
    }

    /** The access point drops the frame; the controller sees it before time passes. */
    private fun dropNetwork() {
        network.drop()
        scope.runCurrent()
    }

    @Test
    fun `Router drops the frame at night`() {
        offAtNight()
        dropNetwork()
        assertEquals(1, network.requests, "asked at once")
        advance(Duration.ofSeconds(30))
        assertEquals(1, network.requests, "not more than once a minute")
        advance(Duration.ofSeconds(30))
        assertEquals(2, network.requests)
        minutes(7)
        assertEquals(9, network.requests, "again every minute")
        assertFalse(power.screenOn, "the display stays dark")
        assertEquals(listOf("off"), power.calls)
        assertEquals(listOf("Network lost while the display is off: asking for a reconnect"), logged("Network"))
    }

    @Test
    fun `Network back`() {
        offAtNight()
        dropNetwork()
        minutes(3)
        network.restore()
        scope.runCurrent()
        val asked = network.requests
        minutes(30)
        assertEquals(asked, network.requests, "nothing more is asked")
        assertFalse(power.screenOn)
        assertEquals(listOf("off"), power.calls)
    }

    @Test
    fun `Nothing is asked while the display is on by day`() {
        start()
        dropNetwork()
        minutes(30)
        assertEquals(0, network.requests)
        assertEquals(emptyList<String>(), power.calls)
    }

    @Test
    fun `Nothing is asked while the display is woken by the power button`() {
        offAtNight()
        power.powerButton()
        scope.runCurrent()
        dropNetwork()
        minutes(4)
        assertTrue(power.screenOn)
        assertEquals(0, network.requests)
        advance(Duration.ofSeconds(90))
        assertFalse(power.screenOn, "off after waking")
        assertEquals(1, network.requests, "asked from then on")
    }

    @Test
    fun `A network lost by day and still missing at the off time is asked for from the off time on`() {
        start()
        until("2026-10-21T21:00")
        dropNetwork()
        until("2026-10-21T21:59")
        assertEquals(0, network.requests)
        minutes(1)
        assertFalse(power.screenOn)
        assertEquals(1, network.requests)
        minutes(9)
        assertFalse(power.screenOn, "the ten minutes count from the off time")
        minutes(1)
        assertTrue(power.screenOn)
        assertTrue(controller.dark.value)
    }

    @Test
    fun `The manual switch off counts as off by the frame`() {
        start()
        manual(false)
        dropNetwork()
        assertEquals(1, network.requests)
    }

    // Dark wake for the network

    @Test
    fun `Still no network after ten minutes`() {
        offAtNight()
        dropNetwork()
        minutes(9)
        assertFalse(power.screenOn)
        assertFalse(controller.dark.value)
        minutes(1)
        assertTrue(power.screenOn, "on for the network")
        assertTrue(controller.dark.value, "with a black picture")
        assertEquals(DisplayState(true, false, DisplayCause.SCHEDULE, Instant.parse("2026-10-22T07:00:00Z")), state)
        assertEquals(listOf("Dark wake for the network"), logged("Dark wake"))
    }

    @Test
    fun `The screen-on of a dark wake is not waking`() {
        offAtNight()
        dropNetwork()
        minutes(10)
        assertTrue(controller.dark.value)
        // The screen event of the frame's own switch has been handled; the display is still off by the schedule.
        assertEquals(DisplayCause.SCHEDULE, state.cause)
        assertEquals(emptyList<String>(), logged("Display woken"))
        minutes(1)
        assertFalse(power.screenOn)
        assertEquals(DisplayCause.SCHEDULE, state.cause)
    }

    @Test
    fun `Network back during the dark wake`() {
        offAtNight()
        dropNetwork()
        minutes(10)
        assertTrue(power.screenOn)
        advance(Duration.ofSeconds(15))
        network.restore()
        scope.runCurrent()
        assertFalse(power.screenOn, "off again at once")
        assertFalse(controller.dark.value)
        assertEquals(listOf("Dark wake for the network", "Dark wake ended (network back)"), logged("Dark wake"))
        minutes(30)
        assertEquals(listOf("off", "on", "off"), power.calls)
    }

    @Test
    fun `Network missing for long`() {
        offAtNight()
        dropNetwork()
        var onFor = 0L
        var wakes = 0
        var wasOn = false
        repeat(25 * 6) {
            advance(Duration.ofSeconds(10))
            if (power.screenOn) {
                onFor += 10
                assertTrue(controller.dark.value, "on only for a dark wake")
                assertFalse(state.on)
                if (!wasOn) wakes++
            }
            wasOn = power.screenOn
        }
        assertEquals(2, wakes, "twice in 25 minutes")
        assertEquals(120, onFor, "one minute each")
        assertEquals(listOf("off", "on", "off", "on", "off"), power.calls)
        assertEquals(
            listOf(
                "Dark wake for the network",
                "Dark wake ended (after a minute)",
                "Dark wake for the network",
                "Dark wake ended (after a minute)",
            ),
            logged("Dark wake"),
        )
    }

    @Test
    fun `Touch during the dark wake`() {
        offAtNight()
        dropNetwork()
        minutes(10)
        assertTrue(controller.dark.value)
        controller.userActivity()
        scope.runCurrent()
        assertFalse(controller.dark.value, "the slideshow appears")
        assertTrue(power.screenOn)
        assertEquals(DisplayState(true, true, DisplayCause.WAKE, clock.now().plus(Duration.ofMinutes(5))), state)
        minutes(2)
        controller.userActivity()
        scope.runCurrent()
        minutes(4)
        assertTrue(power.screenOn, "five minutes after the last touch")
        minutes(2)
        assertFalse(power.screenOn)
        assertFalse(controller.dark.value)
        assertEquals(DisplayCause.SCHEDULE, state.cause)
    }

    @Test
    fun `The manual switch on during a dark wake keeps the display on`() {
        offAtNight()
        dropNetwork()
        minutes(10)
        assertTrue(manual(true).on)
        assertFalse(controller.dark.value)
        minutes(30)
        assertTrue(power.screenOn)
        assertEquals(DisplayCause.MANUAL, state.cause)
    }

    @Test
    fun `A dark wake switched off by the power button is over`() {
        offAtNight()
        dropNetwork()
        minutes(10)
        power.powerButton()
        scope.runCurrent()
        assertFalse(controller.dark.value)
        assertEquals(DisplayCause.SCHEDULE, state.cause, "not a manual off")
        minutes(9)
        assertFalse(power.screenOn)
        minutes(1)
        assertTrue(controller.dark.value, "the next one ten minutes later")
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

    // Robustness

    @Test
    fun `A switch that throws is retried and the controller keeps running`() {
        start()
        until("2026-10-21T21:59")
        power.failNextSwitch = IllegalStateException("lock refused")
        minutes(1)
        assertTrue(power.screenOn, "the failed switch did nothing")
        minutes(1)
        assertFalse(power.screenOn, "tried again at the next evaluation")
        assertEquals(listOf("off", "off"), power.calls)
        // The loop is alive: a manual switch is answered.
        assertTrue(manual(true).on)
        power.failNextSwitch = IllegalStateException("lock refused")
        val s = manual(false)
        assertEquals(DisplayCause.MANUAL, s.cause, "answered although the switch failed")
        assertTrue(s.on)
        minutes(1)
        assertFalse(power.screenOn, "tried again at the next evaluation")
    }
}
