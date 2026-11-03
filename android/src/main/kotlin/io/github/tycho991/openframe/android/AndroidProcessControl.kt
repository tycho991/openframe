package io.github.tycho991.openframe.android

import android.content.Context
import android.os.Process
import io.github.tycho991.openframe.core.health.Health
import io.github.tycho991.openframe.core.platform.ProcessControl

/**
 * Restarts the app for leaving safe mode: a planned exit first, so that the end counts neither as a crash nor as an
 * early end, then the display restart, then the end of the process; the service comes back through `START_STICKY`.
 */
internal class AndroidProcessControl(
    private val context: Context,
    private val health: () -> Health,
) : ProcessControl {
    override fun restart(reason: String) {
        health().plannedExit("restart: $reason", VALID_MS)
        FrameApp.scheduleRestart(context, FrameApp.RESTART_DELAY_MS)
        Process.killProcess(Process.myPid())
    }

    private companion object {
        const val VALID_MS = 60_000L
    }
}
