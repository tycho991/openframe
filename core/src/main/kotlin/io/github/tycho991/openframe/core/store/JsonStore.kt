package io.github.tycho991.openframe.core.store

import io.github.tycho991.openframe.core.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicReference

/**
 * JSON for stored files: tolerant of fields removed from older versions. Without debug info, so that an error names
 * the offset but quotes nothing of the file, which may hold secrets.
 */
@OptIn(ExperimentalSerializationApi::class)
val StoreJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        exceptionsWithDebugInfo = false
    }

/**
 * A JSON file that is written atomically through [AtomicFile], so a power loss leaves either the old or the new
 * state, never half a file.
 *
 * [save] batches changes: a single writer flushes the latest state after [debounceMs] at most. [flush] writes
 * immediately.
 *
 * A file that cannot be read is not discarded (spec `frame-settings`, "Durable, atomic storage"): a read error is
 * retried, then [load] returns `null` and the store is [unreadable] without renaming anything. The first write over
 * it keeps it as `<name>.broken-<epochMs>`; the [KEPT_BROKEN] newest of those stay. With [backupOnLoad], a successful
 * load copies the file to `<name>.bak` once, and [load] falls back to that copy when the file is unreadable.
 */
class JsonStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val scope: CoroutineScope,
    private val log: AppLog,
    private val debounceMs: Long = 1000,
    private val backupOnLoad: Boolean = false,
) {
    private val atomic = AtomicFile(file)
    private val backup = File(file.parentFile, file.name + ".bak")
    private val writeLock = Mutex()
    private val signal = Channel<Unit>(Channel.CONFLATED)

    private val pending = AtomicReference<Pending<T>?>(null)
    private val writeFailure = "${file.name} could not be written"

    @Volatile private var backedUp = false

    /** Whether the file is there but could not be read at the last [load], and has not been written since. */
    @Volatile var unreadable = false
        private set

    init {
        scope.launch {
            for (unused in signal) {
                delay(debounceMs)
                try {
                    flush()
                    log.recovered(TAG, writeFailure, "${file.name} written again")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The state is back in `pending`; try again later instead of ending the writer.
                    log.e(TAG, "$writeFailure, trying again in ${RETRY_MS / 1000} s", e)
                    delay(RETRY_MS)
                    signal.trySend(Unit)
                }
            }
        }
    }

    /** Whether the file is there, readable or not. */
    fun exists(): Boolean = file.exists()

    /**
     * Reads the stored state, or `null` if there is none or no readable one. An unreadable file is retried, then
     * left in place and replaced by the copy of the last start if there is one.
     */
    fun load(): T? {
        try {
            if (atomic.tmp.exists()) {
                // An aborted write. The main file is the valid state.
                log.w(TAG, "Incomplete file ${atomic.tmp.name} discarded")
                atomic.tmp.delete()
            }
        } catch (e: Exception) {
            log.w(TAG, "${atomic.tmp.name} could not be removed: $e")
        }
        if (!file.exists()) return null
        when (val main = read(file, READ_RETRIES)) {
            is Read.Ok -> {
                unreadable = false
                if (backupOnLoad) backUp(main.text)
                return main.value
            }

            is Read.Failed -> {
                unreadable = true
                log.e(TAG, "${file.name} not readable (${main.reason}); it stays until it is next written")
            }
        }
        if (!backupOnLoad || !backup.exists()) return null
        return when (val copy = read(backup, 0)) {
            is Read.Ok -> {
                log.w(TAG, "${file.name}: the copy of the last start (${backup.name}) applies")
                copy.value
            }

            is Read.Failed -> {
                log.e(TAG, "${backup.name} not readable either (${copy.reason})")
                null
            }
        }
    }

    private fun read(
        from: File,
        retries: Int,
    ): Read<T> {
        var attempt = 0
        while (true) {
            val text =
                try {
                    from.readText()
                } catch (e: IOException) {
                    if (attempt++ < retries) {
                        Thread.sleep(RETRY_READ_MS)
                        continue
                    }
                    return Read.Failed("${e.javaClass.simpleName}: ${e.message}")
                } catch (e: OutOfMemoryError) {
                    return Read.Failed("OutOfMemoryError")
                }
            return try {
                Read.Ok(StoreJson.decodeFromString(serializer, text), text)
            } catch (e: Exception) {
                // The message of a format error quotes the input, which may hold a password: class and offset only.
                val offset = OFFSET.find(e.message.orEmpty())?.groupValues?.get(1)
                Read.Failed(e.javaClass.simpleName + if (offset != null) " at offset $offset" else "")
            } catch (e: OutOfMemoryError) {
                Read.Failed("OutOfMemoryError")
            } catch (e: StackOverflowError) {
                Read.Failed("StackOverflowError")
            }
        }
    }

    private fun backUp(text: String) {
        if (backedUp) return
        backedUp = true
        try {
            AtomicFile(backup).writeText(text)
        } catch (e: Exception) {
            log.w(TAG, "${backup.name} could not be written: $e")
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

    /**
     * Stores [value] at once, replacing any queued state, with steps that need no extra space: atomically; else in
     * place if the new content is not longer; else by deleting the file (an unreadable one is kept as a broken copy
     * instead). Throws [IOException] only if even that fails.
     *
     * Writing in place is not atomic, and a missing file loads as `null`: only for files whose unreadable or missing
     * state is fail-closed, `clients.json` (nobody is paired) and `secret.json` (a new key).
     */
    suspend fun saveCritical(value: T) {
        writeLock.withLock {
            pending.set(null)
            val bytes = StoreJson.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
            val atomicError =
                try {
                    atomic.write(bytes) { if (unreadable) setAside() }
                    unreadable = false
                    return
                } catch (e: IOException) {
                    e
                }
            try {
                if (!unreadable && file.isFile && bytes.size <= file.length()) {
                    RandomAccessFile(file, "rw").use { out ->
                        out.write(bytes)
                        out.setLength(bytes.size.toLong())
                        out.fd.sync()
                    }
                    log.w(TAG, "${file.name} written in place: ${atomicError.message}")
                    return
                }
            } catch (e: IOException) {
                log.w(TAG, "${file.name} could not be written in place either: ${e.message}")
            }
            if (unreadable) {
                setAside()
                unreadable = false
            } else if (file.exists() && !file.delete()) {
                throw IOException("${file.name} could not be written, written in place or deleted", atomicError)
            }
            log.e(TAG, "${file.name} could not be written and is gone (fail-closed): ${atomicError.message}")
        }
    }

    private fun write(value: T) {
        val text = StoreJson.encodeToString(serializer, value)
        atomic.writeText(text) { if (unreadable) setAside() }
        unreadable = false
    }

    /** Keeps the unreadable file as `<name>.broken-<epochMs>`, never over an existing one, and the newest three of them. */
    private fun setAside() {
        if (!file.exists()) return
        var ms = System.currentTimeMillis()
        var target: File
        do {
            target = File(file.parentFile, "${file.name}$BROKEN${ms++}")
        } while (target.exists())
        if (!file.renameTo(target)) throw IOException("${file.name} could not be kept as ${target.name}")
        log.w(TAG, "The unreadable ${file.name} is kept as ${target.name}")
        file.parentFile
            ?.listFiles()
            .orEmpty()
            .filter { it.name.startsWith(file.name + BROKEN) }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            .drop(KEPT_BROKEN)
            .forEach { it.delete() }
    }

    private sealed interface Read<out T> {
        class Ok<T>(
            val value: T,
            val text: String,
        ) : Read<T>

        class Failed(
            val reason: String,
        ) : Read<Nothing>
    }

    private class Pending<T>(
        val value: T,
    )

    companion object {
        /** How many broken copies of a file are kept. */
        const val KEPT_BROKEN = 3
        private const val BROKEN = ".broken-"
        private const val TAG = "store"
        private const val RETRY_MS = 10_000L
        private const val READ_RETRIES = 3
        private const val RETRY_READ_MS = 100L
        private val OFFSET = Regex("offset (\\d+)")
    }
}
