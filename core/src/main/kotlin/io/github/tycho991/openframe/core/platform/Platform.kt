package io.github.tycho991.openframe.core.platform

import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Time source, replaced by a controllable clock in tests. */
interface Clock {
    fun now(): Instant

    fun millis(): Long = now().toEpochMilli()

    /** The device's time zone, read afresh on each call so that a change of the zone takes effect at once. */
    fun zone(): ZoneId = ZoneId.systemDefault()

    /** Milliseconds that a change of the wall clock does not move, for timers. Only differences are meaningful. */
    fun monotonicMillis(): Long = java.lang.System.nanoTime() / 1_000_000

    object System : Clock {
        override fun now(): Instant = Instant.now()
    }
}

/** Dimensions and properties of the device, read from the system in :android. */
interface PlatformInfo {
    val model: String
    val androidVersion: String
    val appVersion: String

    /** Resolution in the device's natural orientation, without system bars. */
    val screenWidth: Int
    val screenHeight: Int

    /** The language of the system, read afresh on each call; the frame's language `auto` follows it. */
    val systemLocale: Locale

    /**
     * IPv4 addresses that a browser on the local network can reach, best first (see [lanAddresses]). The hint on
     * the frame shows the first. Mobile data and VPN addresses are not included, so with mobile data only the list is
     * empty; a hotspot or tethering of the device's own still gives its address.
     */
    fun ipAddresses(): List<String>

    /** Free storage on the volume that contains [dir]. */
    fun freeBytes(dir: File): Long = dir.usableSpace
}

/** The web UI's files, from the APK assets in :android. */
fun interface AssetSource {
    /** Opens the file at [path] (without a leading slash) or returns `null`. */
    fun open(path: String): InputStream?
}

/**
 * Switching the display really off and on, as the power button does (spec `display-schedule`). In :android this is
 * device administration; when it is not active, [available] is false and nothing can be switched off.
 */
interface DisplayPower {
    /** openframe is a device administrator and can switch the display off. */
    val available: Boolean

    /** The display is on, as the system reports it. */
    val screenOn: Boolean

    /** Switches the display off. `false` if that is not possible (not available). */
    fun turnOff(): Boolean

    fun turnOn()

    fun setListener(listener: Listener)

    interface Listener {
        /** The display went on or off, by the frame itself, the power button or another app. */
        fun screenChanged(on: Boolean)

        /** Device administration was activated or deactivated. */
        fun availabilityChanged()
    }

    companion object {
        /** A device that can never switch its display off. */
        val None: DisplayPower =
            object : DisplayPower {
                override val available = false
                override val screenOn = true

                override fun turnOff() = false

                override fun turnOn() {}

                override fun setListener(listener: Listener) {}
            }
    }
}

/** Reports photo files created or deleted by the app itself to the system so that they become visible over USB. */
fun interface MediaStoreNotifier {
    fun notifyChanged(file: File)

    companion object {
        val None = MediaStoreNotifier { }
    }
}
