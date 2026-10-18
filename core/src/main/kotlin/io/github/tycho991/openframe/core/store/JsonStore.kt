package io.github.tycho991.openframe.core.store

import io.github.tycho991.openframe.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** JSON für gespeicherte Dateien: tolerant gegenüber entfernten Feldern älterer Versionen. */
val StoreJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

/**
 * Eine JSON-Datei, die atomar geschrieben wird: erst `name.tmp`, dann `fsync`,
 * dann `rename`. Ein Stromausfall hinterlässt so entweder den alten oder den
 * neuen Stand, nie eine halbe Datei.
 *
 * [save] bündelt Änderungen: Ein einziger Schreiber setzt höchstens nach
 * [debounceMs] den jeweils letzten Stand ab. [flush] schreibt sofort.
 */
class JsonStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val scope: CoroutineScope,
    private val log: AppLog,
    private val debounceMs: Long = 1000,
) {
    private val tmp = File(file.parentFile, file.name + ".tmp")
    private val writeLock = Mutex()
    private val signal = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var pending: Pending<T>? = null

    init {
        scope.launch {
            for (unused in signal) {
                delay(debounceMs)
                flush()
            }
        }
    }

    /** Liest den gespeicherten Stand oder `null`, wenn es keinen lesbaren gibt. */
    fun load(): T? {
        if (tmp.exists()) {
            // Ein abgebrochener Schreibvorgang. Die Hauptdatei ist der gültige Stand.
            log.w(TAG, "Unvollständige Datei ${tmp.name} verworfen")
            tmp.delete()
        }
        if (!file.exists()) return null
        return try {
            StoreJson.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            val broken = File(file.parentFile, file.name + ".broken")
            log.e(TAG, "${file.name} nicht lesbar, beiseitegelegt als ${broken.name}", e)
            file.renameTo(broken)
            null
        }
    }

    /** Merkt [value] zum Schreiben vor. Mehrere Aufrufe kurz hintereinander ergeben einen Schreibvorgang. */
    fun save(value: T) {
        pending = Pending(value)
        signal.trySend(Unit)
    }

    /** Schreibt einen vorgemerkten Stand sofort. */
    suspend fun flush() {
        writeLock.withLock {
            val value = pending ?: return
            pending = null
            write(value.value)
        }
    }

    /** Schreibt [value] sofort, an der Bündelung vorbei. */
    suspend fun saveNow(value: T) {
        writeLock.withLock {
            pending = null
            write(value)
        }
    }

    private fun write(value: T) {
        file.parentFile?.mkdirs()
        val text = StoreJson.encodeToString(serializer, value)
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        // rename(2) ersetzt die Zieldatei atomar. java.nio.file gibt es auf Android erst ab API 26.
        if (!tmp.renameTo(file)) throw IOException("${tmp.name} ließ sich nicht nach ${file.name} umbenennen")
    }

    private class Pending<T>(
        val value: T,
    )

    private companion object {
        const val TAG = "store"
    }
}
