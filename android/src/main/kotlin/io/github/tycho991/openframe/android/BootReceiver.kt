package io.github.tycho991.openframe.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Starts service and display after boot and after Android replaced the app with a new version (spec `kiosk-host`,
 * "Restart after an update"). The start of the core, which follows either, logs when openframe is not the HOME app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val replaced = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != ACTION_QUICKBOOT && !replaced) return
        FrameService.start(context)
        // From Android 10 apps may not start an activity from the background. As the HOME launcher the display appears there by itself.
        if (Build.VERSION.SDK_INT < 29) {
            context.startActivity(Intent(context, DisplayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    companion object {
        /** Some manufacturers, for example older Lenovo devices, send only this action on quick boot. */
        private const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"

        /**
         * The package the Home button leads to, `android` while the system's chooser asks, `null` without one. The app
         * cannot choose itself: `addPreferredActivity` is signature-protected.
         */
        fun homePackage(context: Context): String? =
            context.packageManager
                .resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName

        /** A warning in the core's log when the Home button does not lead to openframe (design decision 13). */
        fun logHome(context: Context) {
            val home = homePackage(context)
            if (home == context.packageName) return
            (context.applicationContext as FrameApp).core.log.w(
                "app",
                "openframe is not the HOME app (${home ?: "none"}): the Home button does not lead to the slideshow",
            )
        }
    }
}
