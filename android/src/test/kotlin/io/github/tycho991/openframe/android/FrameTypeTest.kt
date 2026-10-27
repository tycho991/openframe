package io.github.tycho991.openframe.android

import android.graphics.Typeface
import android.widget.TextView
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Spec `visual-design`: Typeface Inter, loaded from the APK's assets. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class FrameTypeTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `All four weights load from the assets`() {
        for (weight in FrameType.Weight.entries) {
            val typeface = FrameType.typeface(context.assets, weight)
            assertNotNull(weight.name, typeface)
            assertNotSame(weight.name, Typeface.DEFAULT, typeface)
        }
    }

    @Test
    fun `Each weight is loaded once`() {
        val weight = FrameType.Weight.MEDIUM
        assertSame(FrameType.typeface(context.assets, weight), FrameType.typeface(context.assets, weight))
    }

    @Test
    fun `A view gets the weight's typeface`() {
        val view = TextView(context)
        FrameType.apply(FrameType.Weight.REGULAR, view)
        assertSame(FrameType.typeface(context.assets, FrameType.Weight.REGULAR), view.typeface)
    }

    @Test
    fun `A font that cannot be loaded gives no typeface instead of an error`() {
        assertNull(FrameType.load(context.assets, "fonts/missing.ttf"))
    }
}
