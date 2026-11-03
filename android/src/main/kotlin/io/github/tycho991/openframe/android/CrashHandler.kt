package io.github.tycho991.openframe.android

import android.content.Context
import android.os.Process
import android.util.Log
import io.github.tycho991.openframe.core.health.RunJournal

/**
 * Self-healing of the app process (design decision 5 of `crash-resilience`): every step in its own `try`, in this
 * order: the restart of the display, with a growing delay from the third early end in a row so that the display does
 * not cover an open installation dialog; the Java crash in the [journal]; the log entry, only if the core exists
 * ([log]); then the end of the process. The service comes back through `START_STICKY` and its watchdog anyway.
 *
 * The default handler is not called: it reports the crash to the system, and on some devices (Frameo) that shows an
 * error dialog that keeps the process alive until "OK" is tapped, so the restart would never come.
 */
internal class CrashHandler(
    private val context: Context,
    private val journal: RunJournal,
    /** Early ends in a row before this run, 0 once the run is stable. */
    private val earlyEnds: () -> Int,
    private val log: (Thread, Throwable) -> Unit,
    private val kill: () -> Unit = { Process.killProcess(Process.myPid()) },
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(
        thread: Thread,
        error: Throwable,
    ) {
        try {
            FrameApp.scheduleRestart(context, restartDelayMs(earlyEnds()))
        } catch (ignored: Throwable) {
            // Do not risk anything more during a crash.
        }
        try {
            journal.onJavaCrash(error.javaClass.name)
        } catch (ignored: Throwable) {
        }
        try {
            log(thread, error)
        } catch (ignored: Throwable) {
        }
        // What the default handler would have put into Logcat; the report to the system is given up on purpose.
        Log.e("AndroidRuntime", "FATAL EXCEPTION: ${thread.name}\nProcess: ${context.packageName}, PID: ${Process.myPid()}", error)
        kill()
    }

    companion object {
        /** The delay of the display restart after this crash, the ([earlyEndsBefore] + 1)th early end in a row. */
        fun restartDelayMs(earlyEndsBefore: Int): Long =
            when (earlyEndsBefore + 1) {
                in 0..2 -> FrameApp.RESTART_DELAY_MS
                3 -> 30_000L
                4 -> 2 * 60_000L
                else -> 10 * 60_000L
            }
    }
}
