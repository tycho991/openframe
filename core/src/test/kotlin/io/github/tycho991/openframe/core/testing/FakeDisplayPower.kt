package io.github.tycho991.openframe.core.testing

import io.github.tycho991.openframe.core.platform.DisplayPower

/**
 * Display that records what the frame switches and reports screen events like the system: switching it sends the
 * event, and the test sends the power button and changes of device administration.
 */
class FakeDisplayPower(
    available: Boolean = true,
) : DisplayPower {
    /** `off` and `on`, in the order the frame called them. */
    val calls = mutableListOf<String>()

    @Volatile
    override var available: Boolean = available
        private set

    @Volatile
    override var screenOn: Boolean = true
        private set

    private var listener: DisplayPower.Listener? = null

    override fun turnOff(): Boolean {
        calls += "off"
        if (!available) return false
        screen(false)
        return true
    }

    override fun turnOn() {
        calls += "on"
        screen(true)
    }

    override fun setListener(listener: DisplayPower.Listener) {
        this.listener = listener
    }

    /** The power button: switches the display over, as a person would. */
    fun powerButton() = screen(!screenOn)

    /** The display goes on or off by something other than the frame, e.g. another app's schedule. */
    fun screen(on: Boolean) {
        if (screenOn == on) return
        screenOn = on
        listener?.screenChanged(on)
    }

    /** A screen event that does not match the screen, as Android sends a SCREEN_ON before the first SCREEN_OFF after boot. */
    fun staleEvent(on: Boolean) {
        listener?.screenChanged(on)
    }

    /** Device administration is activated or deactivated in the system settings. */
    fun setAvailable(value: Boolean) {
        available = value
        listener?.availabilityChanged()
    }
}
