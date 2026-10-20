package io.github.tycho991.openframe.core.platform

import java.io.File
import java.io.InputStream
import java.time.Instant

/** Time source, replaced by a controllable clock in tests. */
interface Clock {
    fun now(): Instant

    fun millis(): Long = now().toEpochMilli()

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

    /** IPv4 addresses on the LAN that the hint on the frame shows. */
    fun ipAddresses(): List<String>

    /** Free storage on the volume that contains [dir]. */
    fun freeBytes(dir: File): Long = dir.usableSpace
}

/** The web UI's files, from the APK assets in :android. */
fun interface AssetSource {
    /** Opens the file at [path] (without a leading slash) or returns `null`. */
    fun open(path: String): InputStream?
}

/** Reports photo files created or deleted by the app itself to the system so that they become visible over USB. */
fun interface MediaStoreNotifier {
    fun notifyChanged(file: File)

    companion object {
        val None = MediaStoreNotifier { }
    }
}
