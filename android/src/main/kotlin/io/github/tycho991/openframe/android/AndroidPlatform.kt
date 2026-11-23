package io.github.tycho991.openframe.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import io.github.tycho991.openframe.core.platform.AddressCandidate
import io.github.tycho991.openframe.core.platform.AssetSource
import io.github.tycho991.openframe.core.platform.ConnectionKind
import io.github.tycho991.openframe.core.platform.InterfaceAddress
import io.github.tycho991.openframe.core.platform.LocalAddresses
import io.github.tycho991.openframe.core.platform.MediaStoreNotifier
import io.github.tycho991.openframe.core.platform.PlatformInfo
import io.github.tycho991.openframe.core.platform.kindOfInterfaceName
import io.github.tycho991.openframe.core.platform.lanAddresses
import io.github.tycho991.openframe.core.platform.localAddresses
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

/**
 * The kind of a network from its transports, VPN first: the address shown on the frame and the [AndroidNetworkLink]
 * use it.
 */
internal fun connectionKind(capabilities: NetworkCapabilities): ConnectionKind =
    when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> ConnectionKind.VPN
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionKind.CELLULAR
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionKind.WIFI
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionKind.ETHERNET
        else -> ConnectionKind.OTHER
    }

/** Device data from the system. */
class AndroidPlatformInfo(
    private val context: Context,
) : PlatformInfo {
    override val model: String = Build.MODEL ?: "Android"
    override val androidVersion: String = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()

    override val appVersion: String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    override val appVersionCode: Long? =
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }.getOrNull()

    override val appDebuggable: Boolean = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Read once per process (design decision 1a of `open-android-settings`). */
    override val otherActivityEndsApp: Boolean by lazy { frameoFirmware(context.packageManager) }

    private val natural: Pair<Int, Int> by lazy { naturalSize() }

    override val screenWidth: Int get() = natural.first
    override val screenHeight: Int get() = natural.second

    // The system's configuration, not the activity's, which carries the frame's chosen language.
    @Suppress("DEPRECATION")
    override val systemLocale: Locale get() = Resources.getSystem().configuration.locale

    override fun ipAddresses(): List<String> =
        lanAddresses(
            interfaceAddresses().mapNotNull { a ->
                (a.address as? Inet4Address)?.hostAddress?.let { AddressCandidate(it, a.kind) }
            },
        )

    override fun localAddresses(): LocalAddresses = localAddresses(interfaceAddresses())

    /** The app's internal folder always is; a folder on shared storage only while that is mounted. */
    override fun storageMounted(dir: File): Boolean =
        dir.absolutePath.startsWith(context.filesDir.absolutePath) ||
            Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED

    /**
     * Every address of the device, IPv4 and IPv6, with the kind of connection it is on. The system says what a network is
     * (Wi-Fi, mobile data, VPN); interface names differ by vendor and cannot tell. Interfaces that no network of the
     * device owns, such as a Wi-Fi hotspot or USB tethering, are classified by their name ([kindOfInterfaceName]), so
     * that a stacked 464XLAT interface (`v4-rmnet_data0`) or a mobile data interface of a network the system did not
     * describe still counts as mobile data. The same holds if the system cannot be asked at all.
     */
    private fun interfaceAddresses(): List<InterfaceAddress> {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
        val fromInterfaces = { skip: Set<String> ->
            interfaces
                .filter { !it.isLoopback && it.isUp && it.name !in skip }
                .flatMap { iface -> iface.inetAddresses.toList().map { InterfaceAddress(it, kindOfInterfaceName(iface.name)) } }
        }
        return runCatching {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val owned = mutableSetOf<String>()

            // getAllNetworks is deprecated from API 31 but still works; its replacement, a NetworkCallback, is
            // asynchronous, and the address is read synchronously.
            @Suppress("DEPRECATION")
            val networks = connectivity.allNetworks
            val classified =
                networks.flatMap { network ->
                    val capabilities = connectivity.getNetworkCapabilities(network)
                    val properties = connectivity.getLinkProperties(network)
                    // A network the system describes only in part (while it comes up or goes down) still owns its
                    // interface: without a kind its addresses are not shown at all.
                    properties?.interfaceName?.let { owned += it }
                    if (capabilities == null || properties == null) return@flatMap emptyList()
                    val kind = connectionKind(capabilities)
                    properties.linkAddresses.map { InterfaceAddress(it.address, kind) }
                }
            classified + fromInterfaces(owned)
        }.getOrElse { fromInterfaces(emptySet()) }
    }

    /** Resolution in the device's natural orientation, independent of the current rotation. */
    @Suppress("DEPRECATION")
    private fun naturalSize(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        val rotated = display.rotation == Surface.ROTATION_90 || display.rotation == Surface.ROTATION_270
        return if (rotated) metrics.heightPixels to metrics.widthPixels else metrics.widthPixels to metrics.heightPixels
    }

    companion object {
        /** The frame app of the Frameo firmware, which ends openframe whenever another app's activity starts. */
        const val FRAMEO_FRAME_APP = "net.frameo.frame"

        /**
         * The package manager knows the Frameo's frame app, also disabled or uninstalled for the user: a frame that runs
         * openframe as its kiosk usually has it disabled, not removed from the system image. The manifest declares the
         * package in `<queries>` for API 30 and newer.
         */
        @Suppress("DEPRECATION")
        fun frameoFirmware(packageManager: PackageManager): Boolean {
            val flags =
                if (Build.VERSION.SDK_INT >= 24) {
                    PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
                } else {
                    PackageManager.GET_UNINSTALLED_PACKAGES or PackageManager.GET_DISABLED_COMPONENTS
                }
            return try {
                packageManager.getPackageInfo(FRAMEO_FRAME_APP, flags)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }
}

/** The built web UI from the APK assets under `webadmin/`. */
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

/** Reports files to the MediaStore so that a computer over USB (MTP) sees them, and no longer sees deleted ones. */
class AndroidMediaStoreNotifier(
    private val context: Context,
) : MediaStoreNotifier {
    override fun notifyChanged(file: java.io.File) {
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
    }
}
