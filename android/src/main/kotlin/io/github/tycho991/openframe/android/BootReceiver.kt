package io.github.tycho991.openframe.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Starts service and display after boot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != ACTION_QUICKBOOT) return
        FrameService.start(context)
        // From Android 10 apps may not start an activity from the background. As the HOME launcher the display appears there by itself.
        if (Build.VERSION.SDK_INT < 29) {
            context.startActivity(Intent(context, DisplayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private companion object {
        /** Some manufacturers, for example older Lenovo devices, send only this action on quick boot. */
        const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
    }
}
