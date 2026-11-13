package io.github.tycho991.openframe.android

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.github.tycho991.openframe.core.platform.RawBrightness
import io.github.tycho991.openframe.core.platform.ScreenBrightness

/**
 * Android's system brightness in `Settings.System` (design decision 1 of `add-display-brightness`). Writing needs
 * `WRITE_SETTINGS`: granted with the installation up to API 22, allowed by the user in the system's "Modify system
 * settings" screen from API 23. The decisions are made in :core.
 */
class AndroidScreenBrightness(
    context: Context,
) : ScreenBrightness {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    override val available: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context)

    override val autoSupported: Boolean =
        (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager?)?.getDefaultSensor(Sensor.TYPE_LIGHT) != null

    override fun read(): RawBrightness =
        RawBrightness(
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, MAX),
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, MANUAL) == AUTOMATIC,
        )

    override fun write(target: RawBrightness): Boolean =
        try {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, target.value)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, if (target.auto) AUTOMATIC else MANUAL)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Brightness refused", e)
            false
        }

    /** The system's screen that allows openframe to modify system settings, from API 23; `null` below, where it is granted. */
    fun permissionIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            null
        }

    private companion object {
        const val TAG = "brightness"
        const val MAX = 255
        const val MANUAL = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        const val AUTOMATIC = Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    }
}
