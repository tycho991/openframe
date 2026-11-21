package io.github.tycho991.openframe.android

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Spec `kiosk-host`: Android settings hidden where the firmware ends the app (design decision 1a of `open-android-settings`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FrameoFirmwareTest {
    private val context = RuntimeEnvironment.getApplication()
    private val packageManager get() = context.packageManager

    private fun installFrameApp(enabled: Boolean) {
        val info =
            PackageInfo().apply {
                packageName = AndroidPlatformInfo.FRAMEO_FRAME_APP
                applicationInfo =
                    ApplicationInfo().apply {
                        packageName = AndroidPlatformInfo.FRAMEO_FRAME_APP
                        this.enabled = enabled
                    }
            }
        shadowOf(packageManager).installPackage(info)
        if (!enabled) {
            packageManager.setApplicationEnabledSetting(
                AndroidPlatformInfo.FRAMEO_FRAME_APP,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                0,
            )
        }
    }

    @Test
    fun `The Frameo firmware is recognized by its frame app`() {
        installFrameApp(enabled = true)
        assertTrue(AndroidPlatformInfo.frameoFirmware(packageManager))
        assertTrue(AndroidPlatformInfo(context).otherActivityEndsApp)
    }

    @Test
    fun `Also when the frame app is disabled`() {
        installFrameApp(enabled = false)
        assertTrue(AndroidPlatformInfo.frameoFirmware(packageManager))
    }

    @Test
    @Config(sdk = [23])
    fun `Also when the frame app is disabled below API 24`() {
        installFrameApp(enabled = false)
        assertTrue(AndroidPlatformInfo.frameoFirmware(packageManager))
    }

    @Test
    fun `Without the frame app no firmware is recognized`() {
        assertFalse(AndroidPlatformInfo.frameoFirmware(packageManager))
        assertFalse(AndroidPlatformInfo(context).otherActivityEndsApp)
    }
}
