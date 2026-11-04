package io.github.tycho991.openframe.android

import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import io.github.tycho991.openframe.core.platform.DecoderUnavailableException
import io.github.tycho991.openframe.core.platform.GeoPoint
import io.github.tycho991.openframe.core.platform.ImageException
import io.github.tycho991.openframe.core.platform.ImageFormat
import io.github.tycho991.openframe.core.platform.ImageInfo
import io.github.tycho991.openframe.core.platform.ImageMetadata
import io.github.tycho991.openframe.core.platform.MediaEngine
import io.github.tycho991.openframe.core.platform.Region
import io.github.tycho991.openframe.core.platform.SkipReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Design decision 1 of `crash-resilience`: the protocol to the decoder process and what a death of it means. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RemoteMediaEngineTest {
    /** Answers like a decoder; `boom` files throw what [failure] says. */
    private class ScriptedEngine : MediaEngine {
        val renders: MutableList<List<Any>> = Collections.synchronizedList(mutableListOf())

        override fun probe(file: File): ImageInfo =
            when (file.name) {
                "heic.jpg" -> throw ImageException(SkipReason.UNSUPPORTED, "HEIC only from Android 9")
                "io.jpg" -> throw IOException("disk")
                "oom.jpg" -> throw OutOfMemoryError("heap")
                "bare.jpg" -> ImageInfo(ImageFormat.PNG, 10, 20, null, null)
                else -> ImageInfo(ImageFormat.JPEG, 4000, 3000, Instant.parse("2024-06-01T10:15:30.123Z"), GeoPoint(43.3188, 11.3308))
            }

        override fun readMetadata(file: File): ImageMetadata =
            if (file.name ==
                "none.jpg"
            ) {
                ImageMetadata.NONE
            } else {
                ImageMetadata(GeoPoint(-22.9, -43.2))
            }

        override fun render(
            source: File,
            target: File,
            width: Int,
            height: Int,
            region: Region,
            quality: Int,
        ) {
            if (source.name == "corrupt.jpg") throw ImageException(SkipReason.CORRUPT, "Not decodable")
            renders += listOf(source.path, target.path, width, height, region, quality)
        }
    }

    /** One decoder process: it dies on [poison], and every call in flight then fails as it would on a device. */
    private inner class FakeDecoder(
        val pid: Int,
    ) : DecoderBinder(scripted) {
        @Volatile var dead = false
        val release = CountDownLatch(1)

        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            if (dead) throw DeadObjectException()
            if (code == DecoderProtocol.PID) {
                // Each fake decoder is a process of its own: status OK, then its pid.
                reply!!.writeInt(0)
                reply.writeInt(pid)
                return true
            }
            run {
                val start = data.dataPosition()
                val path = data.readString()!!
                data.setDataPosition(start)
                when {
                    path.endsWith(poison) -> {
                        die()
                        throw DeadObjectException()
                    }

                    path.endsWith("hang.jpg") || (path.endsWith(waitsForPoison) && pid == 1) -> {
                        inside.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        if (dead) throw DeadObjectException()
                    }
                }
            }
            return super.onTransact(code, data, reply, flags)
        }

        fun die() {
            dead = true
            release.countDown()
        }

        override fun isBinderAlive() = !dead
    }

    private inner class FakeConnector : DecoderConnector {
        val decoders: MutableList<FakeDecoder> = Collections.synchronizedList(mutableListOf())
        val killed: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        var disconnects = 0
        var available = true

        override fun connect(timeoutMs: Long): IBinder? = if (available) FakeDecoder(decoders.size + 1).also { decoders += it } else null

        override fun disconnect() {
            disconnects++
        }

        override fun kill(pid: Int) {
            killed += pid
            decoders.firstOrNull { it.pid == pid }?.die()
        }
    }

    private val scripted = ScriptedEngine()
    private var poison = "poison.jpg"
    private var waitsForPoison = "never"
    private val inside = CountDownLatch(1)
    private val connector = FakeConnector()

    private fun engine(
        idleMs: Long = 60_000,
        watchdogMs: Long = 60_000,
    ) = RemoteMediaEngine(connector, idleMs = idleMs, watchdogMs = watchdogMs)

    private fun reason(block: () -> Unit): SkipReason {
        try {
            block()
        } catch (e: ImageException) {
            return e.reason
        }
        fail("ImageException expected")
        throw AssertionError()
    }

    @Test
    fun everyCallAndItsErrorsGoThroughTheParcel() {
        val engine = engine()
        assertEquals(
            ImageInfo(ImageFormat.JPEG, 4000, 3000, Instant.parse("2024-06-01T10:15:30.123Z"), GeoPoint(43.3188, 11.3308)),
            engine.probe(File("/photos/a.jpg")),
        )
        assertEquals(ImageInfo(ImageFormat.PNG, 10, 20, null, null), engine.probe(File("/photos/bare.jpg")))
        assertEquals(ImageMetadata(GeoPoint(-22.9, -43.2)), engine.readMetadata(File("/photos/a.jpg")))
        assertEquals(ImageMetadata.NONE, engine.readMetadata(File("/photos/none.jpg")))
        engine.render(File("/photos/a.jpg"), File("/cache/a.jpg"), 1024, 600, Region(0.1, 0.2, 0.5, 0.6), 80)
        assertEquals(listOf("/photos/a.jpg", "/cache/a.jpg", 1024, 600, Region(0.1, 0.2, 0.5, 0.6), 80), scripted.renders.single())

        val unsupported =
            try {
                engine.probe(File("/photos/heic.jpg"))
                null
            } catch (e: ImageException) {
                e
            }
        assertEquals(SkipReason.UNSUPPORTED, unsupported!!.reason)
        assertEquals("HEIC only from Android 9", unsupported.message)
        assertEquals(SkipReason.CORRUPT, reason { engine.render(File("/photos/corrupt.jpg"), File("/cache/c.jpg"), 1, 1, Region.FULL, 80) })
        assertTrue(runCatching { engine.probe(File("/photos/io.jpg")) }.exceptionOrNull() is IOException)
        // An error in the decoder is an answer, not a crash: no further binding.
        assertTrue(runCatching { engine.probe(File("/photos/oom.jpg")) }.exceptionOrNull() is IOException)
        assertEquals(1, connector.decoders.size)
    }

    @Test
    fun aDeathWithOneCallInFlightIsACrashAndTheNextCallBindsAfresh() {
        val engine = engine()
        engine.probe(File("/photos/a.jpg"))
        assertEquals(SkipReason.CRASHED, reason { engine.probe(File("/photos/poison.jpg")) })
        assertEquals(1, connector.disconnects)
        assertEquals(ImageFormat.JPEG, engine.probe(File("/photos/b.jpg")).format)
        assertEquals(2, connector.decoders.size, "a fresh decoder")
    }

    @Test
    fun withTwoCallsInFlightOnlyTheOneThatKillsTheDecoderOnItsOwnIsACrash() {
        waitsForPoison = "good.jpg"
        val engine = engine()
        val pool = Executors.newFixedThreadPool(2)
        val good = pool.submit<ImageInfo> { engine.probe(File("/photos/good.jpg")) }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        val bad = pool.submit<SkipReason> { reason { engine.probe(File("/photos/poison.jpg")) } }

        assertEquals(ImageFormat.JPEG, good.get(5, TimeUnit.SECONDS).format, "repeated alone and answered normally")
        assertEquals(SkipReason.CRASHED, bad.get(5, TimeUnit.SECONDS), "killed the decoder alone as well")
        assertEquals(2, connector.decoders.count { it.dead }, "the decoder died once with both calls and once on the repeated poison alone")
        pool.shutdown()
    }

    @Test
    fun aCallThatHangsIsEndedByTheWatchdogAndCountsAsACrash() {
        val engine = engine(watchdogMs = 200)
        assertEquals(SkipReason.CRASHED, reason { engine.probe(File("/photos/hang.jpg")) })
        assertEquals(listOf(1), connector.killed)
    }

    @Test
    fun withoutCallsTheDecoderIsUnboundAndEnded() {
        val engine = engine(idleMs = 200)
        engine.probe(File("/photos/a.jpg"))
        val deadline = System.currentTimeMillis() + 5_000
        while (connector.killed.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(listOf(1), connector.killed)
        assertEquals(1, connector.disconnects)
        engine.probe(File("/photos/b.jpg"))
        assertEquals(2, connector.decoders.size)
    }

    @Test
    fun aDecoderThatCannotBeBoundIsUnavailable() {
        connector.available = false
        assertTrue(runCatching { engine().probe(File("/photos/a.jpg")) }.exceptionOrNull() is DecoderUnavailableException)
        connector.available = true
        assertFalse(runCatching { engine().probe(File("/photos/a.jpg")) }.isFailure)
    }

    @Test
    fun theDecoderProcessCreatesNoCore() {
        val app = RuntimeEnvironment.getApplication() as FrameApp
        app.start("io.github.tycho991.openframe:decode")
        assertFalse(app.coreCreated)
    }

    private fun assertEquals(
        expected: Int,
        actual: Int,
        message: String,
    ) = org.junit.Assert.assertEquals(message, expected.toLong(), actual.toLong())

    private fun assertEquals(
        expected: Any?,
        actual: Any?,
        message: String,
    ) = org.junit.Assert.assertEquals(message, expected, actual)
}
