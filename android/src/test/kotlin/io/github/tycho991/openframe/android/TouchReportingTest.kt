package io.github.tycho991.openframe.android

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** Spec `display-schedule`: Waking during an off period › Touches extend the time, also inside a dialog. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TouchReportingTest {
    /** A dialog's own callback: records every call and handles touches and keys. */
    private val calls = mutableListOf<String>()
    private val inner =
        Proxy.newProxyInstance(Window.Callback::class.java.classLoader, arrayOf(Window.Callback::class.java)) { _, method, _ ->
            calls += method.name
            if (method.returnType == Boolean::class.javaPrimitiveType) true else null
        } as Window.Callback

    @Test
    fun `A touch is reported and still reaches the dialog`() {
        var touches = 0
        val callback = reportingTouches(inner) { touches++ }
        val time = SystemClock.uptimeMillis()
        assertTrue(callback.dispatchTouchEvent(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 10f, 10f, 0)))
        assertEquals(1, touches)
        assertEquals(listOf("dispatchTouchEvent"), calls)
    }

    @Test
    fun `Other calls are passed on without a touch`() {
        var touches = 0
        val callback = reportingTouches(inner) { touches++ }
        assertTrue(callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
        callback.onWindowFocusChanged(true)
        assertEquals(0, touches)
        assertEquals(listOf("dispatchKeyEvent", "onWindowFocusChanged"), calls)
    }
}
