package io.github.tycho991.openframe.android

import android.content.ComponentName
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.github.tycho991.openframe.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Spec `display-schedule`: Dark wake for the network › Still no network after ten minutes, on the display. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DarkWakeDisplayTest {
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `Black at the lowest brightness during a dark wake, restored after it`() {
        // Without the core: the flow comes from the test.
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).declareComponentUnbindable(ComponentName(app, FrameService::class.java))
        val activity = Robolectric.buildActivity(DisplayActivity::class.java).setup().get()
        val flow = MutableStateFlow(false)
        val scope = CoroutineScope(Dispatchers.Main.immediate)
        scope.launch { activity.followDark(flow) }
        idle()
        val black = activity.findViewById<View>(R.id.dark)
        assertEquals(View.GONE, black.visibility)
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, activity.window.attributes.screenBrightness, 0f)

        flow.value = true
        idle()
        assertEquals(View.VISIBLE, black.visibility)
        assertTrue("above the overlays", black.translationZ > activity.findViewById<View>(R.id.hint).translationZ)
        assertEquals(0.01f, activity.window.attributes.screenBrightness, 0f)
        val time = SystemClock.uptimeMillis()
        assertTrue(
            "a touch on the black display goes no further",
            activity.dispatchTouchEvent(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 10f, 10f, 0)),
        )

        flow.value = false
        idle()
        assertEquals(View.GONE, black.visibility)
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, activity.window.attributes.screenBrightness, 0f)
        scope.cancel()
    }
}
