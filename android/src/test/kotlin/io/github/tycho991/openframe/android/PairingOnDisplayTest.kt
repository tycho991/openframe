package io.github.tycho991.openframe.android

import android.content.ComponentName
import android.os.Looper
import android.view.View
import android.widget.TextView
import io.github.tycho991.openframe.R
import io.github.tycho991.openframe.core.FrameCore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.io.File

/**
 * Spec `admin-access` › Code only while shown on the frame › Local-only mode (design decision 9 of
 * `android-platform-fixes`). The display binds the real [FrameService], which hands out a light core with an empty
 * library and no paired device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PairingOnDisplayTest {
    private val app = RuntimeEnvironment.getApplication() as FrameApp
    private lateinit var core: FrameCore

    @After
    fun stop() {
        if (::core.isInitialized) runBlocking { core.stop() }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

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

    private fun display(): DisplayActivity {
        core = TestCore.create(File(app.cacheDir, "pairing-core-${System.nanoTime()}"))
        app.coreFactory = { core }
        runBlocking { core.start() }
        val service = Robolectric.buildService(FrameService::class.java).create().get()
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, FrameService::class.java), service.onBind(null))
        val activity = Robolectric.buildActivity(DisplayActivity::class.java).setup().get()
        idle()
        return activity
    }

    @Test
    fun `No pairing code in local-only mode`() {
        val activity = display()
        val emptyCode = activity.findViewById<TextView>(R.id.empty_code)
        val emptyQr = activity.findViewById<TextView>(R.id.empty_qr)
        waitFor("code in the empty state") { core.pairing.display.value.code != null }
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.empty_state).visibility)
        assertEquals(View.VISIBLE, emptyCode.visibility)

        core.setLocalOnly(true)
        waitFor("code withdrawn") { core.pairing.display.value.code == null }
        assertNull(core.pairing.display.value.url)
        assertEquals(View.GONE, emptyCode.visibility)
        assertEquals(View.GONE, emptyQr.visibility)

        core.setLocalOnly(false)
        waitFor("code again") { core.pairing.display.value.code != null }
        assertNotNull(core.pairing.display.value.code)
        assertEquals(View.VISIBLE, emptyCode.visibility)
    }
}
