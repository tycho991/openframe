package io.github.tycho991.openframe.android

import android.annotation.SuppressLint
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

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        acquireLocks()
        watchNetwork()
        registerDebugCommands()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        (application as FrameApp).startCore()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onDestroy() {
        networkCallback?.let { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) }
        debugReceiver?.let(::unregisterReceiver)
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

        fun start(context: Context) {
            val intent = Intent(context, FrameService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
