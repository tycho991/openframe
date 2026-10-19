package io.github.tycho991.openframe.android

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import io.github.tycho991.openframe.core.FrameCore
import io.github.tycho991.openframe.core.FrameDirs
import io.github.tycho991.openframe.core.log.LogLevel
import io.github.tycho991.openframe.core.log.LogSink
import kotlinx.coroutines.launch
import java.io.File

/**
 * Hält den einen [FrameCore] des Prozesses. Gestartet wird er vom
 * [FrameService], die Anzeige bekommt ihn über dessen Binder.
 */
class FrameApp : Application() {
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
        )
    }

    private val started =
        java.util.concurrent.atomic
            .AtomicBoolean(false)
    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** Startet den Kern genau einmal pro Prozess. */
    fun startCore() {
        if (!started.compareAndSet(false, true)) return
        appScope.launch {
            try {
                core.start()
            } catch (e: Exception) {
                core.log.e("app", "Start des Kerns fehlgeschlagen", e)
                throw e
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    /**
     * Selbstheilung: Absturz protokollieren und die Anzeige in 5 Sekunden neu
     * starten lassen. Der Service startet wegen `START_STICKY` ohnehin neu.
     */
    private fun installCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                core.log.e("crash", "Absturz in ${thread.name}", error)
                scheduleRestart(this, RESTART_DELAY_MS)
            } catch (ignored: Throwable) {
                // Beim Absturz nichts mehr riskieren.
            }
            // Nicht an den Standard-Handler weitergeben: Der meldet den Absturz dem System, und das
            // zeigt auf manchen Geräten (Frameo) einen Fehlerdialog, der den Prozess bis zum Tippen
            // auf „OK“ am Leben hält. Dann käme der Neustart nie. Deshalb den Prozess selbst beenden.
            Process.killProcess(Process.myPid())
            kotlin.system.exitProcess(10)
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
            alarms.set(AlarmManager.RTC, System.currentTimeMillis() + delayMs, pending)
        }
    }
}

/** Protokoll zusätzlich nach Logcat. */
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
