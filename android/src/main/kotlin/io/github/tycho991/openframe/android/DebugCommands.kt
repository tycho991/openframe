package io.github.tycho991.openframe.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build

/**
 * Commands for tests on a device, sent with `adb shell am broadcast -a <action>`. They exist only in debuggable builds,
 * and only a sender holding [PERMISSION] reaches them: the shell holds it, an ordinary app on the frame cannot get it.
 */
internal object DebugCommands {
    const val PERMISSION = Manifest.permission.DUMP

    /** Registers [onCommand] for [action] in a debuggable build and returns its receiver; returns null otherwise. */
    fun register(
        context: Context,
        action: String,
        onCommand: () -> Unit,
    ): BroadcastReceiver? {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) = onCommand()
            }
        val filter = IntentFilter(action)
        // Exported so that adb can send the broadcast. The flag does not exist before API 33.
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, PERMISSION, null, Context.RECEIVER_EXPORTED)
        } else {
            @SuppressLint("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter, PERMISSION, null)
        }
        return receiver
    }
}
