package io.github.tycho991.openframe.core.testing

import io.github.tycho991.openframe.core.platform.RawBrightness
import io.github.tycho991.openframe.core.platform.ScreenBrightness

/**
 * Android's brightness setting that records what the frame writes; the test changes it "on the device" and grants or
 * withdraws the permission.
 */
class FakeScreenBrightness(
    available: Boolean = true,
    autoSupported: Boolean = true,
    value: RawBrightness = RawBrightness(128, auto = false),
) : ScreenBrightness {
    /** Every write of the frame, in order. */
    val writes = mutableListOf<RawBrightness>()

    @Volatile
    override var available: Boolean = available

    @Volatile
    override var autoSupported: Boolean = autoSupported

    /** The setting as Android has it now. */
    @Volatile
    var current: RawBrightness = value
        private set

    override fun read(): RawBrightness = current

    override fun write(target: RawBrightness): Boolean {
        if (!available) return false
        writes += target
        current = target
        return true
    }

    /** Someone changes the brightness in Android's quick settings. */
    fun onDevice(value: RawBrightness) {
        current = value
    }
}
