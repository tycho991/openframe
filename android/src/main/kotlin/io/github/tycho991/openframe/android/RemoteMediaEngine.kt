package io.github.tycho991.openframe.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import io.github.tycho991.openframe.core.platform.DecoderUnavailableException
import io.github.tycho991.openframe.core.platform.ImageException
import io.github.tycho991.openframe.core.platform.ImageInfo
import io.github.tycho991.openframe.core.platform.ImageMetadata
import io.github.tycho991.openframe.core.platform.MediaEngine
import io.github.tycho991.openframe.core.platform.Region
import io.github.tycho991.openframe.core.platform.SkipReason
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Binds the decoder process and ends it; [ServiceDecoderConnector] on the device, a fake in tests. */
internal interface DecoderConnector {
    /** Binds afresh and returns the decoder's binder, `null` if it is not there within [timeoutMs]. */
    fun connect(timeoutMs: Long): IBinder?

    /** Gives up the binding of the last [connect]. */
    fun disconnect()

    /** Ends the decoder process. */
    fun kill(pid: Int)
}

/**
 * [MediaEngine] that calls the [DecoderService] in the process `:decode` (design decision 1 of `crash-resilience`).
 *
 * It binds on the first call and waits up to [connectTimeoutMs], then holds the binding while calls come; after
 * [idleMs] without a call it unbinds and ends the decoder process. If the decoder cannot be bound, a call throws
 * [DecoderUnavailableException].
 *
 * A `DeadObjectException` during a call means the decoder died on it: with one call in flight that call fails as
 * [SkipReason.CRASHED] at once. With several, each is repeated once, one at a time, so that only a call that kills
 * the decoder on its own becomes [SkipReason.CRASHED]. After a death the next call binds afresh instead of waiting for
 * the system to restart the service. A call that has not returned after [watchdogMs] ends the decoder process and is
 * handled as a crash.
 */
internal class RemoteMediaEngine(
    private val connector: DecoderConnector,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    private val idleMs: Long = IDLE_MS,
    private val watchdogMs: Long = WATCHDOG_MS,
    private val timers: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { Thread(it, "decoder-timers").apply { isDaemon = true } },
) : MediaEngine {
    private class Connection(
        val binder: IBinder,
        val pid: Int,
    ) {
        val inFlight = AtomicInteger()

        /** Set by the first call that saw the decoder die: how many calls were in flight then. Guarded by the engine. */
        var callsAtDeath = 0
        var dead = false
    }

    private sealed interface Attempt<out T> {
        class Done<T>(
            val value: T,
        ) : Attempt<T>

        class Died(
            val callsInFlight: Int,
        ) : Attempt<Nothing>
    }

    private val lock = Any()
    private var connection: Connection? = null
    private var idleTask: ScheduledFuture<*>? = null

    /** Ordinary calls share it; a repeated call after a death holds it alone. */
    private val isolation = ReentrantReadWriteLock(true)

    override fun probe(file: File): ImageInfo = call { DecoderProtocol.probe(it, file) }

    override fun readMetadata(file: File): ImageMetadata = call { DecoderProtocol.readMetadata(it, file) }

    override fun render(
        source: File,
        target: File,
        width: Int,
        height: Int,
        region: Region,
        quality: Int,
    ) = call { DecoderProtocol.render(it, source, target, width, height, region, quality) }

    /** Debug builds only: the decoder crashes at its next render (`DEBUG_DECODE_CRASH`). Binds if needed. */
    fun crashNextRender() {
        isolation.read {
            try {
                DecoderProtocol.crashNextRender(connection().binder)
            } catch (e: RemoteException) {
                throw IOException("The decoder could not be called: $e", e)
            }
        }
    }

    private fun <T> call(block: (IBinder) -> T): T {
        val first = isolation.read { attempt(block) }
        if (first is Attempt.Done) return first.value
        if ((first as Attempt.Died).callsInFlight <= 1) throw crashed()
        // Several calls were in flight when the decoder died: this one again, alone.
        val again = isolation.write { attempt(block) }
        if (again is Attempt.Done) return again.value
        throw crashed()
    }

    private fun <T> attempt(block: (IBinder) -> T): Attempt<T> {
        val c = connection()
        c.inFlight.incrementAndGet()
        val watchdog = timers.schedule({ connector.kill(c.pid) }, watchdogMs, TimeUnit.MILLISECONDS)
        try {
            return Attempt.Done(block(c.binder))
        } catch (e: DeadObjectException) {
            return Attempt.Died(died(c))
        } catch (e: RemoteException) {
            throw IOException("The decoder could not be called: $e", e)
        } finally {
            watchdog.cancel(false)
            c.inFlight.decrementAndGet()
            scheduleIdle()
        }
    }

    /** The bound decoder, bound afresh if there is none or the last one died. */
    private fun connection(): Connection =
        synchronized(lock) {
            connection?.let { if (!it.dead && it.binder.isBinderAlive) return it else drop(it) }
            val binder =
                connector.connect(connectTimeoutMs)
                    ?: throw DecoderUnavailableException("The decoder could not be started within ${connectTimeoutMs / 1000} s")
            val pid =
                try {
                    DecoderProtocol.pid(binder)
                } catch (e: RemoteException) {
                    connector.disconnect()
                    throw DecoderUnavailableException("The decoder died right after it started", e)
                }
            Connection(binder, pid).also { connection = it }
        }

    /** Marks [c] dead and returns how many calls were in flight when it died, as the first call to notice saw it. */
    private fun died(c: Connection): Int =
        synchronized(lock) {
            if (!c.dead) c.callsAtDeath = c.inFlight.get()
            drop(c)
            c.callsAtDeath
        }

    private fun drop(c: Connection) {
        c.dead = true
        if (connection === c) {
            connection = null
            connector.disconnect()
        }
    }

    private fun scheduleIdle() {
        synchronized(lock) {
            idleTask?.cancel(false)
            val c = connection ?: return
            if (c.inFlight.get() > 0) return
            idleTask =
                timers.schedule({
                    synchronized(lock) {
                        if (connection !== c || c.inFlight.get() > 0) return@schedule
                        drop(c)
                        connector.kill(c.pid)
                    }
                }, idleMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun crashed() = ImageException(SkipReason.CRASHED, "The decoder process died or hung on this file")

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val IDLE_MS = 2 * 60_000L
        const val WATCHDOG_MS = 60_000L
    }
}

/** Binds [DecoderService] from the application context. */
internal class ServiceDecoderConnector(
    context: Context,
) : DecoderConnector {
    private val context = context.applicationContext
    private var bound: ServiceConnection? = null

    @Synchronized
    override fun connect(timeoutMs: Long): IBinder? {
        disconnect()
        val connected = CountDownLatch(1)
        val binder = AtomicReference<IBinder?>()
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    service: IBinder?,
                ) {
                    binder.set(service)
                    connected.countDown()
                }

                // The engine notices a death at its next call and binds afresh; the system's restart is not waited for.
                override fun onServiceDisconnected(name: ComponentName?) {}
            }
        bound = connection
        if (!context.bindService(Intent(context, DecoderService::class.java), connection, Context.BIND_AUTO_CREATE)) {
            disconnect()
            return null
        }
        if (!connected.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            disconnect()
            return null
        }
        return binder.get()
    }

    @Synchronized
    override fun disconnect() {
        val connection = bound ?: return
        bound = null
        try {
            context.unbindService(connection)
        } catch (e: IllegalArgumentException) {
            // Not bound (any more).
        }
    }

    override fun kill(pid: Int) {
        if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid)
    }
}
