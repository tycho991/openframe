package io.github.tycho991.openframe.android

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.SystemClock
import io.github.tycho991.openframe.core.FrameCore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import java.io.File
import java.time.Duration

/**
 * Spec `kiosk-host`: Restart after an update; spec `app-update`: Display awake during the installation dialog and the
 * uptime the updater compares across restarts (design decisions 6, 8 and 13 of `app-auto-update`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29])
class UpdateRestartTest {
    private val app = RuntimeEnvironment.getApplication() as FrameApp
    private var core: FrameCore? = null

    @After
    fun stop() {
        core?.let { runBlocking { it.stop() } }
    }

    private fun lightCore(): FrameCore =
        TestCore.create(File(app.cacheDir, "restart-core-${System.nanoTime()}")).also {
            core = it
            app.coreFactory = { it }
        }

    @Test
    fun `After the app was replaced, the service starts again and below API 29 the display`() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(FrameService::class.java.name, shadowOf(app).nextStartedService.component?.className)
        val display = shadowOf(app).nextStartedActivity
        if (Build.VERSION.SDK_INT < 29) {
            assertEquals(DisplayActivity::class.java.name, display.component?.className)
        } else {
            assertNull("the HOME role brings the slideshow back", display)
        }
    }

    @Test
    fun `Another action starts nothing`() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_PACKAGE_REPLACED))
        assertNull(shadowOf(app).nextStartedService)
    }

    /** Spec `kiosk-host`: Restart after an update › HOME preference lost. */
    @Test
    fun `A warning when another app is the HOME app`() {
        val c = lightCore()
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pm = shadowOf(app.packageManager)
        pm.removeResolveInfosForIntent(home, app.packageName)
        pm.addResolveInfoForIntent(
            home,
            ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = "com.example.launcher" } },
        )
        BootReceiver.logHome(app)
        val warning = c.log.recent(100).single { "HOME" in it.message }
        assertEquals(
            "openframe is not the HOME app (com.example.launcher): the Home button does not lead to the slideshow",
            warning.message,
        )
    }

    @Test
    fun `No warning while openframe is the HOME app`() {
        val c = lightCore()
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pm = shadowOf(app.packageManager)
        pm.removeResolveInfosForIntent(home, "com.example.launcher")
        pm.addResolveInfoForIntent(
            home,
            ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = app.packageName } },
        )
        assertEquals(app.packageName, BootReceiver.homePackage(app))
        BootReceiver.logHome(app)
        assertTrue(c.log.recent(100).none { "HOME" in it.message })
    }

    /** Spec `app-update`: Display awake during the installation dialog › Short screen timeout. */
    @Test
    fun `The screen is kept on during the dialog, with the deadline as timeout`() {
        val power = AndroidDisplayPower(app)
        power.keepOn(true, 600_000)
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(lock.isHeld)
        assertEquals("openframe:installer", shadowOf(lock).tag)
        power.keepOn(false, 0)
        assertFalse(lock.isHeld)
        // The deadline as a safety net: released by itself after the timeout.
        power.keepOn(true, 600_000)
        val timed = ShadowPowerManager.getLatestWakeLock()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(599))
        assertTrue(timed.isHeld)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertFalse(timed.isHeld)
        // Twice in a row holds one lock, not two.
        power.keepOn(true, 600_000)
        val second = ShadowPowerManager.getLatestWakeLock()
        power.keepOn(true, 300_000)
        assertFalse(second.isHeld)
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        power.keepOn(false, 0)
    }

    @Test
    fun `The core's uptime follows SystemClock`() {
        val before = AndroidClock.elapsedRealtimeMillis()
        assertEquals(SystemClock.elapsedRealtime(), before)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(before + 5000, AndroidClock.elapsedRealtimeMillis())
        assertEquals(SystemClock.elapsedRealtime(), AndroidClock.elapsedRealtimeMillis())
    }

    @Test
    fun `The app's core gets the uptime clock and the installer`() {
        val c = app.coreFactory()
        core = c
        assertSame(AndroidClock, c.clock)
        assertTrue(c.platform.appVersionCode!! > 0)
    }
}
