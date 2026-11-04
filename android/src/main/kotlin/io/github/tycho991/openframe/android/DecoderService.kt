package io.github.tycho991.openframe.android

import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.IBinder

/**
 * Probe, render and EXIF reading of photos in the process `:decode` (spec `kiosk-host`, "Decoding in a separate
 * process"). A native crash of a decoder ends only this process; [RemoteMediaEngine] in the app process notices it
 * during the call. The decoding itself is [AndroidMediaEngine], unchanged.
 */
class DecoderService : Service() {
    private val binder by lazy {
        DecoderBinder(AndroidMediaEngine(), debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
