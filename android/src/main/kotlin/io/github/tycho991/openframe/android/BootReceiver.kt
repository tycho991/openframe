package io.github.tycho991.openframe.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Startet nach dem Hochfahren Service und Anzeige. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != ACTION_QUICKBOOT) return
        FrameService.start(context)
        // Ab Android 10 dürfen Apps aus dem Hintergrund keine Activity starten. Als HOME-Launcher erscheint die Anzeige dort von selbst.
        if (Build.VERSION.SDK_INT < 29) {
            context.startActivity(Intent(context, DisplayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private companion object {
        /** Manche Hersteller, etwa ältere Lenovo-Geräte, senden beim Schnellstart nur diese Aktion. */
        const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
    }
}
