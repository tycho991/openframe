package io.github.tycho991.openframe.android

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import io.github.tycho991.openframe.core.platform.DisplayPower

/**
 * Switches the display as the power button does: off with [DevicePolicyManager.lockNow], which needs openframe to be
 * a device administrator with the policy `force-lock` and nothing else, on with a wake lock that wakes the screen.
 * After that the display's `FLAG_KEEP_SCREEN_ON` holds it on. The decisions are made in :core.
 */
class AndroidDisplayPower(
    context: Context,
) : DisplayPower {
    private val policy = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Volatile
    private var listener: DisplayPower.Listener? = null

    /** Held during the installation dialog, see [keepOn]. */
    private var keepLock: PowerManager.WakeLock? = null

    val admin = ComponentName(context, DisplayAdminReceiver::class.java)

    override val available: Boolean get() = policy.isAdminActive(admin)

    override val screenOn: Boolean get() = power.isInteractive

    override fun turnOff(): Boolean =
        try {
            policy.lockNow()
            true
        } catch (e: SecurityException) {
            false
        }

    @Suppress("DEPRECATION")
    override fun turnOn() {
        power
            .newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "openframe:display-on",
            ).acquire(WAKE_MS)
    }

    /**
     * A screen wake lock without `ACQUIRE_CAUSES_WAKEUP` while the installation dialog is open: touches in Android's
     * installer never reach the display's activity, so a short screen timeout would switch the screen off under it.
     * [timeoutMs], the dialog's deadline, is a safety net should the release never come.
     */
    @Synchronized
    @Suppress("DEPRECATION")
    override fun keepOn(
        on: Boolean,
        timeoutMs: Long,
    ) {
        keepLock?.let { if (it.isHeld) it.release() }
        keepLock = null
        if (!on) return
        keepLock =
            power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "openframe:installer").apply {
                setReferenceCounted(false)
                acquire(timeoutMs)
            }
    }

    override fun setListener(listener: DisplayPower.Listener) {
        this.listener = listener
    }

    /** From the `SCREEN_ON`/`SCREEN_OFF` receiver of the [FrameService]. */
    fun screenChanged(on: Boolean) {
        listener?.screenChanged(on)
    }

    fun availabilityChanged() {
        listener?.availabilityChanged()
    }

    /** Ends device administration, from the device menu. Afterwards openframe can be uninstalled. */
    fun deactivate() {
        policy.removeActiveAdmin(admin)
    }

    /** The system's dialog that activates device administration, with [explanation] for the user. */
    fun activationIntent(explanation: String): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explanation)

    private companion object {
        /** Long enough for the display activity to take over with its own flag. */
        const val WAKE_MS = 5_000L
    }
}

/**
 * openframe as a device administrator, with no policy but locking the screen (`res/xml/device_admin.xml`). Activated
 * in the device menu or with `adb shell dpm set-active-admin`.
 */
class DisplayAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(
        context: Context,
        intent: Intent,
    ) {
        (context.applicationContext as FrameApp).displayPower.availabilityChanged()
    }

    override fun onDisabled(
        context: Context,
        intent: Intent,
    ) {
        (context.applicationContext as FrameApp).displayPower.availabilityChanged()
    }
}
