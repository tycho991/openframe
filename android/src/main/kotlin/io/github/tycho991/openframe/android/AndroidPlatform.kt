package io.github.tycho991.openframe.android

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import io.github.tycho991.openframe.core.platform.AssetSource
import io.github.tycho991.openframe.core.platform.MediaStoreNotifier
import io.github.tycho991.openframe.core.platform.PlatformInfo
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface

/** Gerätedaten aus dem System. */
class AndroidPlatformInfo(
    private val context: Context,
) : PlatformInfo {
    override val model: String = Build.MODEL ?: "Android"
    override val androidVersion: String = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()

    override val appVersion: String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    private val natural: Pair<Int, Int> by lazy { naturalSize() }

    override val screenWidth: Int get() = natural.first
    override val screenHeight: Int get() = natural.second

    override fun ipAddresses(): List<String> =
        runCatching {
            NetworkInterface
                .getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .filterNotNull()
        }.getOrDefault(emptyList())

    /** Auflösung in der natürlichen Ausrichtung des Geräts, unabhängig von der aktuellen Drehung. */
    @Suppress("DEPRECATION")
    private fun naturalSize(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        val rotated = display.rotation == Surface.ROTATION_90 || display.rotation == Surface.ROTATION_270
        return if (rotated) metrics.heightPixels to metrics.widthPixels else metrics.widthPixels to metrics.heightPixels
    }
}

/** Die gebaute Web-UI aus den APK-Assets unter `webadmin/`. */
class ApkAssets(
    private val context: Context,
) : AssetSource {
    override fun open(path: String): InputStream? =
        try {
            context.assets.open("webadmin/$path")
        } catch (e: FileNotFoundException) {
            null
        }
}

/** Meldet Dateien dem MediaStore, damit ein Rechner per USB (MTP) sie sieht, auch gelöschte nicht mehr. */
class AndroidMediaStoreNotifier(
    private val context: Context,
) : MediaStoreNotifier {
    override fun notifyChanged(file: java.io.File) {
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
    }
}
