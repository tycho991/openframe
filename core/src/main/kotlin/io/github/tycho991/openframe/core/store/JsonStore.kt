package io.github.tycho991.openframe.core.store

import io.github.tycho991.openframe.core.log.AppLog
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.atomic.AtomicReference

/** JSON for stored files: tolerant of fields removed from older versions. */
val StoreJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

/**
 * A JSON file that is written atomically: first `name.tmp`, then `fsync`,
 * then `rename`. A power loss thus leaves either the old or the new
 * state, never half a file.
 *
 * [save] batches changes: a single writer flushes the latest state after
 * [debounceMs] at most. [flush] writes immediately.
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

    private val pending = AtomicReference<Pending<T>?>(null)

    init {
        scope.launch {
            for (unused in signal) {
                delay(debounceMs)
                try {
                    flush()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The state is back in `pending`; try again later instead of ending the writer.
                    log.e(TAG, "${file.name} could not be written, trying again in ${RETRY_MS / 1000} s", e)
                    delay(RETRY_MS)
                    signal.trySend(Unit)
                }
            }
        }
    }

    /** Reads the stored state or `null` if there is no readable one. */
    fun load(): T? {
        if (tmp.exists()) {
            // An aborted write. The main file is the valid state.
            log.w(TAG, "Incomplete file ${tmp.name} discarded")
            tmp.delete()
        }
        if (!file.exists()) return null
        return try {
            StoreJson.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            val broken = File(file.parentFile, file.name + ".broken")
            log.e(TAG, "${file.name} not readable, set aside as ${broken.name}", e)
            file.renameTo(broken)
            null
        }
    }

    /** Queues [value] for writing. Several calls in quick succession result in one write. */
    fun save(value: T) {
        pending.set(Pending(value))
        signal.trySend(Unit)
    }

    /**
     * Writes a queued state immediately. If writing fails, the state is queued again (unless a newer one
     * arrived meanwhile) and the exception is passed on.
     */
    suspend fun flush() {
        writeLock.withLock {
            // getAndSet: a save() that arrives after this line stays queued and is written by the next flush.
            val taken = pending.getAndSet(null) ?: return
            try {
                write(taken.value)
            } catch (e: Exception) {
                pending.compareAndSet(null, taken)
                throw e
            }
        }
    }

    /** Writes [value] immediately, bypassing the batching. */
    suspend fun saveNow(value: T) {
        writeLock.withLock {
            pending.set(null)
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
        // rename(2) replaces the target file atomically. java.nio.file exists on Android only from API 26.
        if (!tmp.renameTo(file)) throw IOException("${tmp.name} could not be renamed to ${file.name}")
    }

    private class Pending<T>(
        val value: T,
    )

    private companion object {
        const val TAG = "store"
        const val RETRY_MS = 10_000L
    }
}
