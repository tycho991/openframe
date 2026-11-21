package io.github.tycho991.openframe.android

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import io.github.tycho991.openframe.core.FrameCore
import io.github.tycho991.openframe.core.health.Health
import io.github.tycho991.openframe.core.health.HealthState
import io.github.tycho991.openframe.core.health.RunJournal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.time.Duration

/**
 * Spec `kiosk-host`: Android settings from the device menu, Android settings hidden where the firmware ends the app,
 * Way back from Android's settings, System screen from the device menu (design decisions 2, 3, 6 and 7 of
 * `open-android-settings`). The display binds the real [FrameService], which hands out a light core.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidSettingsMenuTest {
    private val app = RuntimeEnvironment.getApplication() as FrameApp
    private lateinit var core: FrameCore
    private val settingsIntent = Intent(Settings.ACTION_SETTINGS)
    private val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    @After
    fun stop() {
        if (::core.isInitialized) runBlocking { core.stop() }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun resolves(
        intent: Intent,
        pkg: String,
    ) = shadowOf(app.packageManager).addResolveInfoForIntent(
        intent,
        ResolveInfo().apply {
            activityInfo =
                ActivityInfo().apply {
                    packageName = pkg
                    name = "$pkg.Main"
                }
        },
    )

    /** A light core on the app's health, as [FrameApp] builds it, and the service that hands it out. */
    private fun startCore(
        settings: Boolean = true,
        platform: TestCore.Platform = TestCore.Platform(),
        health: Health = app.health,
    ) {
        if (settings) resolves(settingsIntent, "com.android.settings")
        core = TestCore.create(File(app.cacheDir, "menu-core-${System.nanoTime()}"), platform = platform, health = health)
        app.coreFactory = { core }
        runBlocking { core.start() }
        val service = Robolectric.buildService(FrameService::class.java).create().get()
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, FrameService::class.java), service.onBind(null))
    }

    private fun display(
        settings: Boolean = true,
        platform: TestCore.Platform = TestCore.Platform(),
        health: Health = app.health,
    ): ActivityController<DisplayActivity> {
        startCore(settings, platform, health)
        val controller = Robolectric.buildActivity(DisplayActivity::class.java).setup()
        idle()
        return controller
    }

    /** A 3-second press in the top left corner, which opens the device menu. */
    private fun openMenu(activity: DisplayActivity): AlertDialog {
        val time = SystemClock.uptimeMillis()
        activity.dispatchTouchEvent(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 1f, 1f, 0))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        activity.dispatchTouchEvent(MotionEvent.obtain(time, time + 3000, MotionEvent.ACTION_UP, 1f, 1f, 0))
        idle()
        return ShadowAlertDialog.getLatestAlertDialog()
    }

    private fun items(menu: AlertDialog) = shadowOf(menu).items.map { it.toString() }

    private fun logged(text: String) = core.log.recent(500).filter { text in it.message }

    /** Waits for the core's display loop, which runs on its own thread. */
    private fun waitForLog(text: String) {
        val deadline = System.currentTimeMillis() + 5000
        while (logged(text).isEmpty()) {
            assertTrue("Timed out waiting for \"$text\"", System.currentTimeMillis() < deadline)
            idle()
            Thread.sleep(20)
        }
    }

    @Test
    fun `The entry stands after the brightness entry and before unpairing`() {
        val menu = openMenu(display().get())
        val entries = items(menu)
        val i = entries.indexOf("Android settings")
        assertTrue(entries.toString(), i > 0)
        assertTrue(entries[i - 1], entries[i - 1].startsWith("Brightness control"))
        assertEquals("Unpair all devices", entries[i + 1])
    }

    /** `kiosk-host` › Android settings from the device menu › No settings activity. */
    @Test
    fun `No entry without an activity for Android's settings`() {
        assertFalse(items(openMenu(display(settings = false).get())).contains("Android settings"))
    }

    /** `kiosk-host` › Android settings hidden where the firmware ends the app › On the Frameo. */
    @Test
    fun `No entry where the firmware ends the app, the other system screens stay`() {
        val entries = items(openMenu(display(platform = TestCore.Platform(otherActivityEndsApp = true)).get()))
        assertFalse(entries.contains("Android settings"))
        assertTrue(entries.toString(), entries.any { it.startsWith("Display schedule") })
        assertTrue(entries.toString(), entries.any { it.startsWith("Brightness control") })
    }

    /** `kiosk-host` › Android settings hidden where the firmware ends the app › Firmware not recognized. */
    @Test
    fun `No entry once a start taught that the firmware ends the app`() {
        val learned = Health(RunJournal(null), HealthState(systemScreenEndsApp = true)) { 0L }
        assertFalse(items(openMenu(display(health = learned).get())).contains("Android settings"))
    }

    /** `kiosk-host` › Android settings from the device menu › In safe mode. */
    @Test
    fun `The entry is offered in safe mode`() {
        val entries = items(openMenu(display(health = Health.safe(System.currentTimeMillis())).get()))
        assertTrue(entries.toString(), entries.contains("Start normally"))
        assertTrue(entries.toString(), entries.contains("Android settings"))
    }

    /** `kiosk-host` › Android settings from the device menu › Open the settings, › Start page again. */
    @Test
    fun `Choosing the entry opens the start page in a cleared task, with a record and a hold`() {
        val activity = display().get()
        val menu = openMenu(activity)
        shadowOf(menu).clickOnItem(items(menu).indexOf("Android settings"))
        idle()
        val started = shadowOf(app).nextStartedActivity
        assertNotNull(started)
        assertEquals(Settings.ACTION_SETTINGS, started.action)
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        assertEquals(flags, started.flags and flags)
        assertTrue(app.health.state.value.systemScreenOpen)
        assertTrue(app.journal.systemScreenOpen(SystemClock.elapsedRealtime()))
        waitForLog("Display held on for a system screen (android settings)")
    }

    /** `kiosk-host` › Way back from Android's settings › HOME app changed in the settings. */
    @Test
    fun `Back in front the record and the hold are withdrawn, and a changed HOME app is logged`() {
        val controller = display()
        val menu = openMenu(controller.get())
        shadowOf(menu).clickOnItem(items(menu).indexOf("Android settings"))
        idle()
        waitForLog("Display held on for a system screen (android settings)")
        shadowOf(app.packageManager).removeResolveInfosForIntent(home, app.packageName)
        resolves(home, "com.example.launcher")

        controller
            .pause()
            .stop()
            .restart()
            .start()
            .resume()
        idle()
        assertFalse(app.health.state.value.systemScreenOpen)
        assertFalse(app.journal.systemScreenOpen(SystemClock.elapsedRealtime()))
        waitForLog("Display hold ended")
        assertEquals(1, logged("openframe is not the HOME app (com.example.launcher)").size)

        // Resuming again without a system screen logs nothing more.
        controller.pause().resume()
        idle()
        assertEquals(1, logged("openframe is not the HOME app").size)
    }

    @Test
    fun `The other system screens of the menu open with a record as well`() {
        val activity = display().get()
        val menu = openMenu(activity)
        shadowOf(menu).clickOnItem(items(menu).indexOfFirst { it.startsWith("Brightness control") })
        idle()
        assertTrue(app.health.state.value.systemScreenOpen)
        waitForLog("Display held on for a system screen (brightness permission)")
    }

    /** Design decision 3: a display created afresh under the screen gets its core only after its first resume. */
    @Test
    fun `A fresh display withdraws the record at its first resume and ends the hold once the core is there`() {
        startCore()
        core.systemScreenOpened("android settings")
        waitForLog("Display held on for a system screen (android settings)")
        shadowOf(app.packageManager).removeResolveInfosForIntent(home, app.packageName)
        resolves(home, "com.example.launcher")

        // Created, started and resumed before the service connection is delivered.
        Robolectric
            .buildActivity(DisplayActivity::class.java)
            .create()
            .start()
            .resume()
        assertFalse("withdrawn without the core", app.health.state.value.systemScreenOpen)
        assertFalse(app.journal.systemScreenOpen(SystemClock.elapsedRealtime()))
        assertTrue("the hold waits for the core", logged("Display hold ended").isEmpty())

        idle()
        waitForLog("Display hold ended")
        assertEquals(1, logged("openframe is not the HOME app (com.example.launcher)").size)
    }
}
