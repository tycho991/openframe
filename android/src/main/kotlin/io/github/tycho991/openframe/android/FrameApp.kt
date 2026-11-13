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
import io.github.tycho991.openframe.core.health.Health
import io.github.tycho991.openframe.core.health.HealthState
import io.github.tycho991.openframe.core.health.RunEnd
import io.github.tycho991.openframe.core.health.RunJournal
import io.github.tycho991.openframe.core.library.PhotoRoot
import io.github.tycho991.openframe.core.log.LogLevel
import io.github.tycho991.openframe.core.log.LogSink
import io.github.tycho991.openframe.core.platform.Clock
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

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

    /** Android's system brightness; the core reads the permission on every evaluation. */
    val screenBrightness: AndroidScreenBrightness by lazy { AndroidScreenBrightness(this) }

    /** The network; the [FrameService] reports its network callbacks to it. */
    val networkLink: AndroidNetworkLink by lazy { AndroidNetworkLink(this) }

    /** The decoder process, bound by the core's first decoding. */
    internal val decoder: RemoteMediaEngine by lazy { RemoteMediaEngine(ServiceDecoderConnector(this)) }

    /** Builds the core; a test may put in a lighter one before anything asks for [core]. */
    internal var coreFactory: () -> FrameCore = {
        val state = File(filesDir, "state")
        val dirs =
            FrameDirs(
                state = state,
                cache = cacheDir,
                photos = photoFolder(File(state, "storage.json")),
                updates = File(filesDir, "update"),
            )
        FrameCore(
            dirs = dirs,
            platform = AndroidPlatformInfo(this),
            engine = decoder,
            assets = ApkAssets(this),
            notifier = AndroidMediaStoreNotifier(this),
            clock = AndroidClock,
            logSinks = listOf(LogcatSink),
            displayPower = displayPower,
            networkLink = networkLink,
            screenBrightness = screenBrightness,
            health = health,
            processControl = AndroidProcessControl(this) { health },
            installer = AndroidAppInstaller(this),
        )
    }

    private val coreHolder = lazy { coreFactory() }

    /** The run journal of the app process (`state/health/runs.json`), started in [onCreate] before the core exists. */
    internal val journal: RunJournal by lazy { RunJournal(File(filesDir, "state/health/runs.json")) }

    @Volatile private var startState = HealthState()

    /** The `boot_id` the journal read at the start of this process. */
    @Volatile private var bootId: String? = null

    private val healthHolder = lazy { Health(journal, startState, bootId) { SystemClock.elapsedRealtime() } }

    /** What the journal decided at the start of this process: normal or safe mode. */
    val health: Health by healthHolder

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

    private val coreStarted =
        java.util.concurrent.atomic
            .AtomicBoolean(false)
    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** Starts the core exactly once per process. */
    fun startCore() {
        if (!coreStarted.compareAndSet(false, true)) return
        appScope.launch {
            startLogged({ core.start() }) { e -> core.log.e("app", "Starting the core failed", e) }
            BootReceiver.logHome(this@FrameApp)
        }
    }

    val core: FrameCore by coreHolder

    /** Whether the core exists, for tests of the decoder process. */
    internal val coreCreated: Boolean get() = coreHolder.isInitialized()

    override fun onCreate() {
        super.onCreate()
        start(processName())
    }

    /**
     * What [onCreate] does in the process named [processName]. In the app process the crash handler comes first, then
     * the journal records the start, so that even a crash right after it counts. After an unexplained end the display
     * restart is scheduled here, because no crash handler ran: the system often brings back only the service.
     */
    internal fun start(processName: String) {
        if (processName.endsWith(DECODER_PROCESS)) {
            installDecoderCrashHandler()
            return
        }
        val earlyEnds = { if (healthHolder.isInitialized()) health.state.value.recentEarlyEnds else startState.recentEarlyEnds }
        Thread.setDefaultUncaughtExceptionHandler(
            CrashHandler(this, journal, earlyEnds, log = { thread, error ->
                if (coreCreated) core.log.e("crash", "Crash in ${thread.name}", error)
            }),
        )
        bootId = bootId()
        startState = journal.onProcessStart(System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootId, installId())
        restartDelayAfter(startState)?.let { delayMs ->
            try {
                scheduleRestart(this, delayMs)
            } catch (e: Exception) {
                Log.w("openframe/app", "Scheduling the display after an unexplained end failed", e)
            }
        }
    }

    /** `null` where the kernel does not let the app read it. */
    private fun bootId(): String? =
        try {
            File("/proc/sys/kernel/random/boot_id").readText().trim().ifEmpty { null }
        } catch (e: Exception) {
            null
        }

    /** Version code and `lastUpdateTime`: a debug build has the version code of the release it was built from. */
    private fun installId(): String =
        try {
            val info = packageManager.getPackageInfo(packageName, 0)

            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            "$code:${info.lastUpdateTime}"
        } catch (e: Exception) {
            ""
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

    companion object {
        const val RESTART_DELAY_MS = 5_000L
        const val DECODER_PROCESS = ":decode"

        /**
         * Runs [start] and passes on any failure after [log] wrote it, which itself may fail: a swallowed failure would
         * leave a half-started core that never counts as an early end.
         */
        internal suspend fun startLogged(
            start: suspend () -> Unit,
            log: (Throwable) -> Unit,
        ) {
            try {
                start()
            } catch (e: Throwable) {
                try {
                    log(e)
                } catch (ignored: Throwable) {
                }
                throw e
            }
        }

        /**
         * The delay of the display restart at a start after [state]'s previous run, or `null` if none is needed. Only
         * after an unexplained end: a Java crash scheduled its restart in the [CrashHandler] already, and the other
         * ends are no failure. The delay grows as after a Java crash, the end is counted in `recentEarlyEnds` already.
         */
        internal fun restartDelayAfter(state: HealthState): Long? =
            if (state.previousEnd == RunEnd.DIED) CrashHandler.restartDelayMs(state.recentEarlyEnds - 1) else null

        fun scheduleRestart(
            context: Context,
            delayMs: Long,
        ) {
            val pending = restartIntent(context, PendingIntent.FLAG_CANCEL_CURRENT)!!
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            // Time since boot: the clock of some devices is set only after the start.
            alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delayMs, pending)
        }

        /** Withdraws a scheduled display restart: the display is there, and a late one could cover another screen. */
        fun cancelRestart(context: Context) {
            val pending = restartIntent(context, PendingIntent.FLAG_NO_CREATE) ?: return
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending)
            pending.cancel()
        }

        private fun restartIntent(
            context: Context,
            flag: Int,
        ): PendingIntent? {
            val intent = Intent(context, DisplayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val flags = flag or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            return PendingIntent.getActivity(context, 0, intent, flags)
        }
    }
}

/**
 * The system clock with the device's uptime from `SystemClock`, which counts on across restarts of the app; the
 * updater compares it with the `boot_id` (design decision 6 of `app-auto-update`).
 */
internal object AndroidClock : Clock {
    override fun now(): Instant = Instant.now()

    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
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
