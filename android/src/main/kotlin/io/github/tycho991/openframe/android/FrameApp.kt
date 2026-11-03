package io.github.tycho991.openframe.android

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.tycho991.openframe.core.FrameCore
import io.github.tycho991.openframe.core.FrameDirs
import io.github.tycho991.openframe.core.library.PhotoRoot
import io.github.tycho991.openframe.core.log.LogLevel
import io.github.tycho991.openframe.core.log.LogSink
import kotlinx.coroutines.launch
import java.io.File

/**
 * Holds the process's one [FrameCore]. It is started by the
 * [FrameService], the display gets it through its binder.
 *
 * In the decoder process `:decode` ([DecoderService]) it creates no core and no alarms; it only ends that process on
 * a crash.
 */
class FrameApp : Application() {
    /** Switching the display; the [FrameService] reports screen events to it, device administration its changes. */
    val displayPower: AndroidDisplayPower by lazy { AndroidDisplayPower(this) }

    /** The network; the [FrameService] reports its network callbacks to it. */
    val networkLink: AndroidNetworkLink by lazy { AndroidNetworkLink(this) }

    private val coreHolder =
        lazy {
            val state = File(filesDir, "state")
            val dirs =
                FrameDirs(
                    state = state,
                    cache = cacheDir,
                    photos = photoFolder(File(state, "storage.json")),
                )
            FrameCore(
                dirs = dirs,
                platform = AndroidPlatformInfo(this),
                engine = RemoteMediaEngine(ServiceDecoderConnector(this)),
                assets = ApkAssets(this),
                notifier = AndroidMediaStoreNotifier(this),
                logSinks = listOf(LogcatSink),
                displayPower = displayPower,
                networkLink = networkLink,
            )
        }

    /**
     * The stored photo folder, or the external one, which is stored then (spec `photo-library`, "Photo folder kept").
     * Internal storage only on a device that never stored a folder and has no shared storage at all.
     */
    private fun photoFolder(record: File): File {
        val choice =
            PhotoRoot.resolve(
                stored = PhotoRoot.read(record),
                external = getExternalFilesDir("photos"),
                expectedExternal = File(Environment.getExternalStorageDirectory(), "Android/data/$packageName/files/photos"),
                internal = File(filesDir, "photos"),
                storage =
                    when (Environment.getExternalStorageState()) {
                        Environment.MEDIA_MOUNTED -> PhotoRoot.Storage.MOUNTED
                        Environment.MEDIA_REMOVED, Environment.MEDIA_UNMOUNTABLE -> PhotoRoot.Storage.NONE
                        else -> PhotoRoot.Storage.NOT_MOUNTED
                    },
            )
        if (choice.store) PhotoRoot.write(record, choice.folder)
        return choice.folder
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

    val core: FrameCore by coreHolder

    /** Whether the core exists, for tests of the decoder process. */
    internal val coreCreated: Boolean get() = coreHolder.isInitialized()

    override fun onCreate() {
        super.onCreate()
        start(processName())
    }

    /** What [onCreate] does in the process named [processName]. */
    internal fun start(processName: String) {
        if (processName.endsWith(DECODER_PROCESS)) installDecoderCrashHandler() else installCrashHandler()
    }

    /** From `/proc/self/cmdline`: `Application.getProcessName` exists only from API 28. */
    private fun processName(): String =
        try {
            File("/proc/self/cmdline").readText().substringBefore('\u0000').trim()
        } catch (e: Exception) {
            ""
        }

    /**
     * The decoder process only writes to Logcat and ends: the default handler could keep it alive behind an error
     * dialog on the Frameo, and the app process binds a new decoder anyway.
     */
    private fun installDecoderCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Log.e("AndroidRuntime", "FATAL EXCEPTION in the decoder: ${thread.name}, PID: ${Process.myPid()}", error)
            Process.killProcess(Process.myPid())
        }
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
        const val DECODER_PROCESS = ":decode"

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
