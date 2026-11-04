package io.github.tycho991.openframe.android

import android.app.AlertDialog
import android.content.ComponentName
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import io.github.tycho991.openframe.R
import io.github.tycho991.openframe.core.FrameCore
import io.github.tycho991.openframe.core.store.AtomicFile
import io.github.tycho991.openframe.core.update.InstallRequest
import io.github.tycho991.openframe.core.update.UpdatePhase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.security.MessageDigest
import java.time.Duration

/**
 * Spec `app-update`: Ready update on the frame and Installation not allowed, on the display (design decision 9 of
 * `app-auto-update`). The display binds the real [FrameService], which hands out a light core with a ready update.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateOnDisplayTest {
    private val app = RuntimeEnvironment.getApplication() as FrameApp
    private val installer = TestCore.RecordingInstaller()
    private lateinit var core: FrameCore

    @After
    fun stop() {
        if (::core.isInitialized) runBlocking { core.stop() }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Leaves 0.4.0 downloaded and verified in [dir], as an earlier run would have. */
    private fun seedReady(dir: File) {
        val apk = javaClass.getResource("/fixtures/update-0.4.0.apk")!!.readBytes()
        File(dir, "update").apply { mkdirs() }.resolve("openframe-0.4.0.apk").writeBytes(apk)
        val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
        val now = System.currentTimeMillis()
        // update.json as UpdateManager writes it; kotlinx.serialization is not on this module's classpath.
        val stored =
            """{"lastAttemptAt":$now,"release":{"tag":"v0.4.0","notes":"Fixes"},"lastCheck":{"atMs":$now,"result":"update_found"},""" +
                """"ready":{"version":"0.4.0","versionCode":400,"file":"openframe-0.4.0.apk","sha256":"$sha","size":${apk.size}}}"""
        val file = File(dir, "state/update.json").apply { parentFile!!.mkdirs() }
        AtomicFile(file).writeText(stored)
    }

    private fun waitFor(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition()) {
            assertTrue("Timed out: $message", System.currentTimeMillis() < deadline)
            idle()
            Thread.sleep(20)
        }
    }

    private fun display(ready: Boolean = true): DisplayActivity {
        val dir = File(app.cacheDir, "display-core-${System.nanoTime()}")
        if (ready) seedReady(dir)
        core = TestCore.create(dir, installer)
        app.coreFactory = { core }
        runBlocking { core.start() }
        if (ready) waitFor("update ready") { core.update.state.value.phase == UpdatePhase.READY }
        val service = Robolectric.buildService(FrameService::class.java).create().get()
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, FrameService::class.java), service.onBind(null))
        val activity = Robolectric.buildActivity(DisplayActivity::class.java).setup().get()
        idle()
        return activity
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

    /** Spec `app-update`: Ready update on the frame › Tap on the frame. */
    @Test
    fun `A ready update is named in the hint and offered before Info`() {
        val activity = display()
        val hint = activity.findViewById<TextView>(R.id.hint_update)
        assertEquals(View.VISIBLE, hint.visibility)
        assertEquals("Update 0.4.0 ready – install it from the frame menu", hint.text.toString())
        val menu = openMenu(activity)
        val entries = items(menu)
        assertEquals(listOf("Install update 0.4.0", "Info"), entries.takeLast(2))
        // "Info" still opens the info.
        shadowOf(menu).clickOnItem(entries.indexOf("Info"))
        idle()
        val info = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(shadowOf(info).message.toString().startsWith("openframe 0.3.0"))
    }

    @Test
    fun `Choosing the entry opens the dialog, with the file work away from the main thread`() {
        val activity = display()
        val menu = openMenu(activity)
        shadowOf(menu).clickOnItem(items(menu).indexOf("Install update 0.4.0"))
        idle()
        waitFor("dialog opened") { installer.calls.any { it.first == "commit" } }
        val main = Looper.getMainLooper().thread.name
        assertEquals(listOf("create", "commit"), installer.calls.map { it.first })
        assertTrue(installer.calls.toString(), installer.calls.none { it.second == main })
        assertEquals(UpdatePhase.INSTALLING, core.update.state.value.phase)
        assertTrue("no notice", ShadowAlertDialog.getLatestAlertDialog() === menu)
    }

    @Test
    fun `Without a ready update there is no entry and no line in the hint`() {
        val activity = display(ready = false)
        assertEquals(View.GONE, activity.findViewById<TextView>(R.id.hint_update).visibility)
        assertTrue(items(openMenu(activity)).none { it.startsWith("Install update") })
    }

    /** Spec `app-update`: Installation not allowed › Unknown sources off. */
    @Test
    fun `Unknown sources off shows a notice and opens no system screen`() {
        installer.allowed = false
        val activity = display()
        val menu = openMenu(activity)
        shadowOf(menu).clickOnItem(items(menu).indexOf("Install update 0.4.0"))
        idle()
        val notice = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(shadowOf(notice).message.toString().startsWith("Android does not allow openframe to install apps"))
        assertTrue("names the setting", "unknown sources" in shadowOf(notice).message.toString())
        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(installer.calls.isEmpty())
        assertEquals(false, core.update.current().installAllowed)
    }

    @Test
    fun `An entry that went stale while the dialog opened shows a notice`() {
        val activity = display()
        val menu = openMenu(activity)
        // Meanwhile a client asked for the installation.
        assertEquals(InstallRequest.Accepted, core.update.install("\"Phone\""))
        shadowOf(menu).clickOnItem(items(menu).indexOf("Install update 0.4.0"))
        idle()
        val notice = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("The installation dialog is already open.", shadowOf(notice).message.toString())
        assertFalse(activity.isFinishing)
        assertTrue(core.update.state.value.phase == UpdatePhase.INSTALLING)
    }
}
