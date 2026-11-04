package io.github.tycho991.openframe.android

import android.app.AlarmManager
import android.content.Context
import android.os.SystemClock
import io.github.tycho991.openframe.core.health.HealthState
import io.github.tycho991.openframe.core.health.RunEnd
import io.github.tycho991.openframe.core.health.RunJournal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlarmManager
import java.io.File

/** Spec `kiosk-host`: Self-healing. The alarms count the time since boot, the watchdog is armed only where it works. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class SelfHealingAlarmsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun nextAlarm(): ShadowAlarmManager.ScheduledAlarm? =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextScheduledAlarm

    @Test
    fun `The watchdog counts the time since boot and wakes the device`() {
        FrameService.scheduleWatchdog(context)
        val alarm = nextAlarm()
        assertNotNull(alarm)
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm!!.type)
        val expected = SystemClock.elapsedRealtime() + FrameService.WATCHDOG_MS
        assertTrue("trigger ${alarm.triggerAtTime} against $expected", kotlin.math.abs(alarm.triggerAtTime - expected) < 1_000)
    }

    @Test
    fun `The restart after a crash counts the time since boot`() {
        FrameApp.scheduleRestart(context, FrameApp.RESTART_DELAY_MS)
        val alarm = nextAlarm()
        assertNotNull(alarm)
        assertEquals(AlarmManager.ELAPSED_REALTIME, alarm!!.type)
        val expected = SystemClock.elapsedRealtime() + FrameApp.RESTART_DELAY_MS
        assertTrue("trigger ${alarm.triggerAtTime} against $expected", kotlin.math.abs(alarm.triggerAtTime - expected) < 1_000)
    }

    @Test
    fun `The watchdog is armed only where a started service is not refused`() {
        assertTrue(FrameService.watchdogNeeded(21))
        assertTrue(FrameService.watchdogNeeded(23))
        assertTrue(FrameService.watchdogNeeded(30))
        assertFalse(FrameService.watchdogNeeded(31))
        assertFalse(FrameService.watchdogNeeded(36))
    }

    private fun journal() = RunJournal(File(context.filesDir, "test-runs.json").apply { delete() })

    private fun assertRestartIn(delayMs: Long) {
        val alarm = nextAlarm()
        assertNotNull(alarm)
        val expected = SystemClock.elapsedRealtime() + delayMs
        assertTrue("trigger ${alarm!!.triggerAtTime} against $expected", kotlin.math.abs(alarm.triggerAtTime - expected) < 1_000)
    }

    /** crash-resilience, design decision 5: the order of the crash handler. */
    @Test
    fun `The crash handler schedules the restart even without a core or with a log that throws`() {
        val journal = journal().also { it.onProcessStart(0, 1_000, "boot", "1") }
        var killed = false
        val handler =
            CrashHandler(context, journal, { 0 }, log = { _, _ -> throw IllegalStateException("no core yet") }, kill = { killed = true })
        handler.uncaughtException(Thread.currentThread(), IllegalArgumentException("boom"))
        assertRestartIn(FrameApp.RESTART_DELAY_MS)
        assertTrue(killed)
        val next = RunJournal(File(context.filesDir, "test-runs.json")).onProcessStart(0, 2_000, "boot", "1")
        assertEquals(RunEnd.CRASHED, next.previousEnd)
        assertEquals("java.lang.IllegalArgumentException", next.previousCrash)
    }

    /** Spec `kiosk-host`: Self-healing › Repeated early ends. */
    @Test
    fun `From the third early end in a row the display comes back after a growing delay`() {
        assertEquals(listOf(5_000L, 5_000L, 30_000L, 120_000L, 600_000L, 600_000L), (0..5).map { CrashHandler.restartDelayMs(it) })
        CrashHandler(context, journal(), { 2 }, log = { _, _ -> }, kill = {}).uncaughtException(Thread.currentThread(), RuntimeException())
        assertRestartIn(30_000L)
    }

    /** Spec `kiosk-host`: Self-healing › Display after an unexplained end. */
    @Test
    fun `After an unexplained end the start schedules the display, after a growing delay from the third`() {
        assertEquals(5_000L, FrameApp.restartDelayAfter(HealthState(previousEnd = RunEnd.DIED, recentEarlyEnds = 0)))
        assertEquals(5_000L, FrameApp.restartDelayAfter(HealthState(previousEnd = RunEnd.DIED, recentEarlyEnds = 2)))
        assertEquals(30_000L, FrameApp.restartDelayAfter(HealthState(previousEnd = RunEnd.DIED, recentEarlyEnds = 3)))
        assertEquals(10 * 60_000L, FrameApp.restartDelayAfter(HealthState(previousEnd = RunEnd.DIED, recentEarlyEnds = 7)))
        for (end in RunEnd.values().filter { it != RunEnd.DIED }) {
            assertNull("$end", FrameApp.restartDelayAfter(HealthState(previousEnd = end, recentEarlyEnds = 3)))
        }

        // Robolectric started the app process once; a second start without a recorded end is an unexplained end.
        val app = RuntimeEnvironment.getApplication() as FrameApp
        assertNull(nextAlarm())
        app.start("io.github.tycho991.openframe")
        assertRestartIn(FrameApp.RESTART_DELAY_MS)
        FrameApp.cancelRestart(context)
        assertNull("it was the restart of the display", nextAlarm())
    }

    @Test
    fun `The display withdraws a waiting restart when it comes back`() {
        FrameApp.scheduleRestart(context, 30_000L)
        assertNotNull(nextAlarm())
        Robolectric.buildActivity(DisplayActivity::class.java).create()
        assertNull(nextAlarm())
        FrameApp.cancelRestart(context)
    }

    @Test
    fun `A failing start of the core is logged and passed on`() {
        val logged = mutableListOf<Throwable>()
        val failure = IllegalStateException("store")
        val thrown = runCatching { runBlocking { FrameApp.startLogged({ throw failure }) { logged += it } } }.exceptionOrNull()
        assertEquals(failure, thrown)
        assertEquals(listOf<Throwable>(failure), logged)
        // Also when the log itself fails.
        val again = runCatching { runBlocking { FrameApp.startLogged({ throw failure }) { throw RuntimeException("log") } } }
        assertEquals(failure, again.exceptionOrNull())
    }
}
