package io.github.tycho991.openframe.android

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import io.github.tycho991.openframe.R
import io.github.tycho991.openframe.core.FrameCore

/**
 * Foreground service with core and HTTP server. Keeps running when the display
 * is in the background or restarting, and keeps CPU and Wi-Fi awake.
 */
class FrameService : Service() {
    inner class LocalBinder : Binder() {
        val core: FrameCore get() = this@FrameService.core
    }

    private val core: FrameCore get() = (application as FrameApp).core
    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var debugReceiver: BroadcastReceiver? = null
    private var screenReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        acquireLocks()
        watchNetwork()
        watchScreen()
        registerDebugCommands()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        (application as FrameApp).startCore()
        scheduleWatchdog(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onDestroy() {
        networkCallback?.let { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) }
        debugReceiver?.let(::unregisterReceiver)
        screenReceiver?.let(::unregisterReceiver)
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        // The core belongs to the process and keeps running. START_STICKY brings the service back.
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder =
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_MIN),
                )
                Notification.Builder(this, CHANNEL)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this).setPriority(Notification.PRIORITY_MIN)
            }
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, DisplayActivity::class.java),
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0,
            )
        val notification =
            builder
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "openframe:server").apply { acquire() }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wifi.createWifiLock(mode, "openframe:server").apply { acquire() }
    }

    /** Rebind after a Wi-Fi change so that the server accepts on the new address. */
    private fun watchNetwork() {
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val restart = Runnable { if (core.ready.value) core.onNetworkChanged() }
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    core.log.i(TAG, "Network available")
                    main.removeCallbacks(restart)
                    main.postDelayed(restart, NETWORK_SETTLE_MS)
                }

                override fun onLost(network: Network) {
                    core.log.i(TAG, "Network lost")
                }
            }
        connectivity.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
        networkCallback = callback
    }

    /**
     * Passes the display going on and off to the core, whoever switched it. These broadcasts can only be received by
     * a receiver registered at runtime, not by one in the manifest.
     */
    private fun watchScreen() {
        val power = (application as FrameApp).displayPower
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_ON -> power.screenChanged(true)
                        Intent.ACTION_SCREEN_OFF -> power.screenChanged(false)
                    }
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        // System broadcasts reach a receiver that is not exported. The flag does not exist before API 33.
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @SuppressLint("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        screenReceiver = receiver
    }

    /**
     * Debug builds only: `adb shell am broadcast -a io.github.tycho991.openframe.DEBUG_CRASH`
     * triggers a crash to check the self-healing.
     */
    private fun registerDebugCommands() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    main.post { throw IllegalStateException("Crash on request (DEBUG_CRASH)") }
                }
            }
        val filter = IntentFilter(ACTION_DEBUG_CRASH)
        // Exported so that adb can send the broadcast. The flag does not exist before API 33.
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @SuppressLint("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        debugReceiver = receiver
    }

    companion object {
        const val ACTION_DEBUG_CRASH = "io.github.tycho991.openframe.DEBUG_CRASH"
        private const val TAG = "service"
        private const val CHANNEL = "server"
        private const val NOTIFICATION_ID = 1
        private const val NETWORK_SETTLE_MS = 2_000L
        internal const val WATCHDOG_MS = 20_000L
        private const val ACTION_WATCHDOG = "io.github.tycho991.openframe.WATCHDOG"

        /**
         * Whether the watchdog is armed on a device with this API level. It exists for firmware like the one of
         * the Frameo (Android 6). From Android 12 (API 31) a foreground service may not be started from the
         * background by an alarm like this one, the system drops the intent and the chain would end silently; such
         * devices do not end the app on their own either, and the alarm would only wake them every [WATCHDOG_MS].
         */
        internal fun watchdogNeeded(sdk: Int) = sdk < 31

        /**
         * Brings the service back after the system killed the app. The Frameo firmware ends the HOME app as soon
         * as another activity starts ("user request after error") and then treats its process as bad: broadcasts
         * and background activity starts are refused, but a started service is not. So an alarm starts the service
         * itself, every [WATCHDOG_MS]. Each start re-arms the next alarm, while the process lives that is cheap.
         * The alarm counts the time since boot: the Frameo starts with its clock in 2021 and corrects it later,
         * and an alarm on the wall clock would then wait until the clock catches up.
         */
        internal fun scheduleWatchdog(context: Context) {
            if (!watchdogNeeded(Build.VERSION.SDK_INT)) return
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, FrameService::class.java).setAction(ACTION_WATCHDOG)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val pending =
                if (Build.VERSION.SDK_INT >= 26) {
                    PendingIntent.getForegroundService(context, 1, intent, flags)
                } else {
                    PendingIntent.getService(context, 1, intent, flags)
                }
            val at = SystemClock.elapsedRealtime() + WATCHDOG_MS
            if (Build.VERSION.SDK_INT >= 23) {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
            } else {
                alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, FrameService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
