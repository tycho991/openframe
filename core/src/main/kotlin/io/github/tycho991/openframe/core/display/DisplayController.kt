package io.github.tycho991.openframe.core.display

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.Clock
import io.github.tycho991.openframe.core.platform.DisplayPower
import io.github.tycho991.openframe.core.platform.NetworkLink
import io.github.tycho991.openframe.core.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.time.Instant

/** The display as `GET /display` reports it. */
data class DisplayState(
    val available: Boolean,
    val on: Boolean,
    val cause: DisplayCause,
    val nextChange: Instant?,
)

/**
 * Switches the display by the schedule, the manual switch and waking (spec `display-schedule`, design decision 4).
 *
 * One coroutine owns the state: it re-evaluates every [TICK_MS] and at once on every event, reading the wall clock and
 * the zone afresh each time. It acts only when the desired state changes and never holds a level, so a display that
 * someone switched off by hand stays off. Screen events are matched against the desired state: one that matches is
 * the frame's own, an on while it wants off is waking, an off while it wants on is switching off by hand.
 *
 * While the display is off by the frame and the network is missing, it asks for the network once a minute and, after
 * ten minutes without it, wakes the display dark for a minute (spec `display-schedule`, Dark wake for the network).
 */
class DisplayController(
    private val scope: CoroutineScope,
    private val power: DisplayPower,
    private val settings: StateFlow<Settings>,
    private val clock: Clock,
    private val log: AppLog,
    private val network: NetworkLink = NetworkLink.None,
) {
    private sealed interface Event {
        data class Screen(
            val on: Boolean,
        ) : Event

        data object Availability : Event

        data object Network : Event

        data object Touch : Event

        data object ScheduleChanged : Event

        class Manual(
            val on: Boolean,
            val reply: CompletableDeferred<DisplayState>,
        ) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(DisplayState(power.available, power.screenOn, DisplayCause.NONE, null))

    val state: StateFlow<DisplayState> = _state.asStateFlow()

    private val _dark = MutableStateFlow(false)

    /**
     * A dark wake: the display is on only so that the system gets the network back. The display shows black at its
     * lowest brightness while it is true; [state] keeps reporting the display off.
     */
    val dark: StateFlow<Boolean> = _dark.asStateFlow()

    /** openframe is a device administrator; without it the manual switch is refused. */
    val available: Boolean get() = power.available

    // Owned by the loop.
    private var override: Override? = null
    private var wakeUntil: Long? = null

    /** The desired state last acted on, `null` while the frame decides nothing. */
    private var applied: Boolean? = null

    /** The display is off by the frame's own decision, not by hand. */
    private var offByFrame = false
    private var wasAvailable = false

    /** Whether the clock was set at the last evaluation, `null` before the first. */
    private var clockWasSet: Boolean? = null

    /** Monotonic time since which the network is missing while the display is off by the frame, `null` otherwise. */
    private var lostAt: Long? = null
    private var lastRequest: Long? = null

    /** The end of the current dark wake, `null` outside one. */
    private var darkUntil: Long? = null
    private var lastDarkEnd: Long? = null

    @Volatile
    private var lastTouch: Long? = null

    private val schedule get() = settings.value.displaySchedule

    fun start() {
        power.setListener(
            object : DisplayPower.Listener {
                override fun screenChanged(on: Boolean) {
                    events.trySend(Event.Screen(on))
                }

                override fun availabilityChanged() {
                    events.trySend(Event.Availability)
                }
            },
        )
        network.setListener { events.trySend(Event.Network) }
        scope.launch {
            settings
                .map { it.displaySchedule }
                .distinctUntilChanged()
                .drop(1)
                .collect { events.send(Event.ScheduleChanged) }
        }
        scope.launch { loop() }
    }

    /** A touch on the display. Moves the end of a waking period; reported at most once a second. */
    fun userActivity() {
        val now = clock.monotonicMillis()
        val last = lastTouch
        if (last != null && now - last < TOUCH_THROTTLE_MS) return
        lastTouch = now
        events.trySend(Event.Touch)
    }

    /** The manual switch. The caller checks [available] first. Answers with the state after switching. */
    suspend fun setManual(on: Boolean): DisplayState {
        val reply = CompletableDeferred<DisplayState>()
        events.send(Event.Manual(on, reply))
        return reply.await()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun loop() {
        wasAvailable = power.available
        guarded {
            grace()
            evaluate()
        }
        while (scope.isActive) {
            // `select` takes an event or times out, never both: a timeout around `receive` could drop an event
            // already taken from the channel, and a manual switch would then never be answered.
            val event =
                select<Event?> {
                    events.onReceive { it }
                    onTimeout(TICK_MS) { null }
                }
            guarded { handle(event) }
            if (event is Event.Manual) event.reply.complete(_state.value)
        }
    }

    /** One failing step, e.g. a switch the firmware refuses, is logged; the loop goes on and tries again next time. */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(TAG, "Display step failed", e)
        }
    }

    private fun handle(event: Event?) {
        when (event) {
            null, Event.Availability, Event.Network -> {}

            is Event.Screen -> {
                screenChanged(event.on)
            }

            Event.Touch -> {
                if (darkUntil != null) {
                    // A touch on the black display wakes it as the power button would.
                    endDark("touch", switchOff = false)
                    woken()
                } else if (wakeUntil != null) {
                    wakeUntil = clock.monotonicMillis() + wakeMillis()
                }
            }

            Event.ScheduleChanged -> {
                grace()
            }

            is Event.Manual -> {
                manual(event.on)
            }
        }
        val available = power.available
        if (available && !wasAvailable) grace()
        wasAvailable = available
        evaluate()
    }

    private fun decide(): Decision =
        DisplayPolicy.decide(
            DisplayInputs(schedule, power.available, override, wakeUntil),
            clock.now(),
            clock.zone(),
            clock.monotonicMillis(),
        )

    /** At the start and on changes: a display that is on is not switched off at once, but as after waking. */
    private fun grace() {
        val d = decide()
        if (d.on == false && d.cause == DisplayCause.SCHEDULE && power.screenOn) {
            wakeUntil = clock.monotonicMillis() + wakeMillis()
        }
    }

    private fun screenChanged(on: Boolean) {
        // An event that the screen no longer agrees with is out of date. Android sends one after every boot: the
        // first switch to off is announced as SCREEN_ON, then SCREEN_OFF.
        if (on != power.screenOn) return
        if (darkUntil != null) {
            // The dark wake's own screen-on is not waking. Switched off during it, e.g. by the power button, it is over.
            if (!on) endDark("switched off", switchOff = false)
            return
        }
        if (on && applied == false) {
            woken()
        } else if (!on && applied == true) {
            // Switched off by hand (the power button or another app): it stays off until the next switching time.
            log.i(TAG, "Display switched off by hand")
            override = Override(false, decide().period)
            wakeUntil = null
            applied = false
            offByFrame = false
        }
    }

    /** Woken, e.g. by the power button: a manual off ends, and in an off period the display stays on a while. */
    private fun woken() {
        log.i(TAG, "Display woken")
        override = null
        val d = decide()
        if (d.on == false && d.cause == DisplayCause.SCHEDULE) wakeUntil = clock.monotonicMillis() + wakeMillis()
    }

    private fun manual(on: Boolean) {
        if (!power.available) return
        log.i(TAG, "Display switched ${if (on) "on" else "off"} by a client")
        wakeUntil = null
        override = Override(on, decide().period)
    }

    /** A clock that is set after the start counts as a change of the schedule: at night the grace applies. */
    private fun checkClock() {
        val now = clock.now()
        val set = DisplayPolicy.clockSet(now)
        val was = clockWasSet
        clockWasSet = set
        if (set == was) return
        if (!set) {
            log.i(TAG, "Clock not set ($now): schedule paused")
        } else if (was == false) {
            log.i(TAG, "Clock set: schedule applies")
            grace()
        }
    }

    private fun evaluate() {
        checkClock()
        val available = power.available
        val mono = clock.monotonicMillis()
        wakeUntil?.let { if (it <= mono) wakeUntil = null }
        val d = decide()
        if (override != null && d.cause != DisplayCause.MANUAL) override = null
        if (d.period?.on != false || !available) wakeUntil = null
        if (!available) override = null
        // The state is published before switching, so that it is current even when the switch throws.
        publish(d)
        try {
            if (d.on != applied) apply(d)
            keepReachable()
        } finally {
            publish(d)
        }
    }

    /** A display on for a dark wake counts as off: the API reports the decision, and the slideshow keeps resting. */
    private fun publish(d: Decision) {
        _state.value = DisplayState(power.available, power.screenOn && darkUntil == null, d.cause, d.nextChange)
    }

    /**
     * While the display is off by the frame and the network is missing: asks for it once a minute and, ten minutes
     * after it was lost or after the last dark wake, wakes the display dark for a minute (design decisions 2 and 3).
     */
    private fun keepReachable() {
        val mono = clock.monotonicMillis()
        darkUntil?.let { end ->
            when {
                // On by a decision now, e.g. the manual switch: the display stays on.
                applied != false -> endDark("display on", switchOff = false)

                network.connected -> endDark("network back", switchOff = true)

                end <= mono -> endDark("after a minute", switchOff = true)
            }
        }
        val off = applied == false && (!power.screenOn || darkUntil != null)
        if (!off || network.connected) {
            lostAt = null
            lastRequest = null
            lastDarkEnd = null
            return
        }
        val since =
            lostAt ?: mono.also {
                lostAt = it
                log.i(TAG, "Network lost while the display is off: asking for a reconnect")
            }
        if (darkUntil == null && mono - (lastDarkEnd ?: since) >= DARK_WAKE_AFTER_MS) startDark(mono)
        val last = lastRequest
        if (last == null || mono - last >= RECONNECT_MS) {
            lastRequest = mono
            network.requestReconnect()
        }
    }

    private fun startDark(mono: Long) {
        log.i(TAG, "Dark wake for the network")
        // Black before the display lights up.
        _dark.value = true
        darkUntil = mono + DARK_WAKE_MS
        power.turnOn()
    }

    /** Ends the dark wake. A switch off that throws leaves it running, so the next evaluation tries again. */
    private fun endDark(
        reason: String,
        switchOff: Boolean,
    ) {
        if (switchOff && power.screenOn) power.turnOff()
        log.i(TAG, "Dark wake ended ($reason)")
        darkUntil = null
        lastDarkEnd = clock.monotonicMillis()
        _dark.value = false
    }

    /** Switches to [d]; it counts as applied only when the switch did not throw, so a failed one is tried again. */
    private fun apply(d: Decision) {
        when (d.on) {
            true -> {
                offByFrame = false
                if (!power.screenOn) {
                    log.i(TAG, "Display on (${d.cause.api})")
                    power.turnOn()
                }
            }

            false -> {
                offByFrame = true
                if (power.screenOn) {
                    log.i(TAG, "Display off (${d.cause.api})")
                    if (!power.turnOff()) log.w(TAG, "Display could not be switched off")
                }
            }

            null -> {
                // The frame stops deciding: a display it switched off itself comes back on, once.
                if (offByFrame && !power.screenOn) {
                    log.i(TAG, "Display on, the frame no longer switches it")
                    power.turnOn()
                }
                offByFrame = false
            }
        }
        applied = d.on
    }

    private fun wakeMillis(): Long = schedule.wakeMinutes * 60_000L

    companion object {
        const val TICK_MS = 30_000L
        private const val TOUCH_THROTTLE_MS = 1_000L
        private const val RECONNECT_MS = 60_000L
        private const val DARK_WAKE_AFTER_MS = 10 * 60_000L
        private const val DARK_WAKE_MS = 60_000L
        private const val TAG = "display"
    }
}
