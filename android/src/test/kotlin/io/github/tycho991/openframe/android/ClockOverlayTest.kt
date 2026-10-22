package io.github.tycho991.openframe.android

import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.tycho991.openframe.core.settings.ClockSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** Spec `slideshow-display`: Clock overlay, and the clock stepping aside while the hint is shown. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class ClockOverlayTest {
    private val context = RuntimeEnvironment.getApplication()
    private val container = LinearLayout(context).apply { layoutParams = FrameLayout.LayoutParams(100, 100) }
    private val overlay = ClockOverlay(context, container, TextView(context), TextView(context))

    private fun apply(enabled: Boolean) = overlay.apply(ClockSettings(enabled = enabled), Locale.GERMANY)

    @Test
    fun `The clock is shown when it is switched on`() {
        apply(enabled = true)
        assertEquals(View.VISIBLE, container.visibility)
    }

    @Test
    fun `The clock steps aside while the hint is shown and comes back`() {
        apply(enabled = true)
        overlay.hidden = true
        assertEquals(View.GONE, container.visibility)
        overlay.hidden = false
        assertEquals(View.VISIBLE, container.visibility)
    }

    @Test
    fun `A clock that is switched off stays hidden when the hint goes`() {
        apply(enabled = false)
        assertEquals(View.GONE, container.visibility)
        overlay.hidden = true
        overlay.hidden = false
        assertEquals(View.GONE, container.visibility)
    }

    @Test
    fun `New settings do not bring the clock back under the hint`() {
        apply(enabled = true)
        overlay.hidden = true
        apply(enabled = true)
        assertEquals(View.GONE, container.visibility)
    }
}
