package io.github.tycho991.openframe.core.platform

import java.io.File
import java.io.InputStream
import java.time.Instant

/** Zeitquelle, in Tests durch eine steuerbare Uhr ersetzt. */
interface Clock {
    fun now(): Instant

    fun millis(): Long = now().toEpochMilli()

    object System : Clock {
        override fun now(): Instant = Instant.now()
    }
}

/** Maße und Eigenschaften des Geräts, in :android aus dem System gelesen. */
interface PlatformInfo {
    val model: String
    val androidVersion: String
    val appVersion: String

    /** Auflösung in der natürlichen Ausrichtung des Geräts, ohne Systemleisten. */
    val screenWidth: Int
    val screenHeight: Int

    /** IPv4-Adressen im LAN, die der Hinweis am Rahmen anzeigt. */
    fun ipAddresses(): List<String>

    /** Freier Speicher auf dem Datenträger, der [dir] enthält. */
    fun freeBytes(dir: File): Long = dir.usableSpace
}

/** Dateien der Web-UI, in :android aus den APK-Assets. */
fun interface AssetSource {
    /** Öffnet die Datei unter [path] (ohne führenden Schrägstrich) oder liefert `null`. */
    fun open(path: String): InputStream?
}

/** Meldet selbst angelegte oder gelöschte Fotodateien an das System, damit sie per USB sichtbar werden. */
fun interface MediaStoreNotifier {
    fun notifyChanged(file: File)

    companion object {
        val None = MediaStoreNotifier { }
    }
}
