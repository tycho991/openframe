package io.github.tycho991.openframe.android

import android.app.AlarmManager
import android.content.Context
import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlarmManager

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
}
