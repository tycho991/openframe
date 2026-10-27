package io.github.tycho991.openframe.android

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.tycho991.openframe.core.FrameCore
import io.github.tycho991.openframe.core.FrameDirs
import io.github.tycho991.openframe.core.log.LogLevel
import io.github.tycho991.openframe.core.log.LogSink
import kotlinx.coroutines.launch
import java.io.File

/**
 * Holds the process's one [FrameCore]. It is started by the
 * [FrameService], the display gets it through its binder.
 */
class FrameApp : Application() {
    /** Switching the display; the [FrameService] reports screen events to it, device administration its changes. */
    val displayPower: AndroidDisplayPower by lazy { AndroidDisplayPower(this) }

    /** The network; the [FrameService] reports its network callbacks to it. */
    val networkLink: AndroidNetworkLink by lazy { AndroidNetworkLink(this) }

    val core: FrameCore by lazy {
        val dirs =
            FrameDirs(
                state = File(filesDir, "state"),
                cache = cacheDir,
                photos = getExternalFilesDir("photos") ?: File(filesDir, "photos"),
            )
        FrameCore(
            dirs = dirs,
            platform = AndroidPlatformInfo(this),
            engine = AndroidMediaEngine(),
            assets = ApkAssets(this),
            notifier = AndroidMediaStoreNotifier(this),
            logSinks = listOf(LogcatSink),
            displayPower = displayPower,
            networkLink = networkLink,
        )
    }

    private val started =
        java.util.concurrent.atomic
            .AtomicBoolean(false)
    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** Starts the core exactly once per process. */
    fun startCore() {
        if (!started.compareAndSet(false, true)) return
        appScope.launch {
            try {
                core.start()
            } catch (e: Exception) {
                core.log.e("app", "Starting the core failed", e)
                throw e
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    /**
     * Self-healing: log the crash and have the display restart in 5 seconds.
     * The service restarts anyway because of `START_STICKY`.
     */
    private fun installCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                core.log.e("crash", "Crash in ${thread.name}", error)
                scheduleRestart(this, RESTART_DELAY_MS)
            } catch (ignored: Throwable) {
                // Do not risk anything more during a crash.
            }
            // Do not hand over to the default handler: it reports the crash to the system, and on
            // some devices (Frameo) that shows an error dialog that keeps the process alive until
            // "OK" is tapped. The restart would then never come. So end the process ourselves. What the
            // default handler would have put into Logcat is written here, the report to the system
            // (`am_crash`, the drop box) is given up on purpose.
            Log.e("AndroidRuntime", "FATAL EXCEPTION: ${thread.name}\nProcess: $packageName, PID: ${Process.myPid()}", error)
            Process.killProcess(Process.myPid())
        }
    }

    companion object {
        const val RESTART_DELAY_MS = 5_000L

        fun scheduleRestart(
            context: Context,
            delayMs: Long,
        ) {
            val intent = Intent(context, DisplayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val flags = PendingIntent.FLAG_CANCEL_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val pending = PendingIntent.getActivity(context, 0, intent, flags)
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            // Time since boot: the clock of some devices is set only after the start.
            alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delayMs, pending)
        }
    }
}

/** Log to Logcat as well. */
private object LogcatSink : LogSink {
    override fun write(entry: io.github.tycho991.openframe.core.log.LogEntry) {
        val tag = "openframe/${entry.tag}"
        when (entry.level) {
            LogLevel.DEBUG -> Log.d(tag, entry.message)
            LogLevel.INFO -> Log.i(tag, entry.message)
            LogLevel.WARN -> Log.w(tag, entry.message)
            LogLevel.ERROR -> Log.e(tag, entry.message)
        }
    }
}
