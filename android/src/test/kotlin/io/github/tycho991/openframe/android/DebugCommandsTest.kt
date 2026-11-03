package io.github.tycho991.openframe.android

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** release-pipeline, design decision 2: the debug commands exist only in debug builds and only adb reaches them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DebugCommandsTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val flags = app.applicationInfo.flags

    @After
    fun restoreFlags() {
        app.applicationInfo.flags = flags
    }

    private fun debuggable(on: Boolean) {
        app.applicationInfo.flags =
            if (on) flags or ApplicationInfo.FLAG_DEBUGGABLE else flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
    }

    private fun registered() = shadowOf(app).registeredReceivers.filter { it.intentFilter.hasAction(ACTION) }

    @Test
    fun `A release build registers nothing`() {
        debuggable(false)
        assertNull(DebugCommands.register(app, ACTION) {})
        assertTrue(registered().isEmpty())
        assertTrue(shadowOf(app).getReceiversForIntent(Intent(ACTION)).isEmpty())
    }

    @Test
    fun `A debug build registers the receiver behind the DUMP permission`() {
        debuggable(true)
        val receiver = DebugCommands.register(app, ACTION) {}
        assertNotNull(receiver)
        val wrapper = registered().single()
        assertEquals(receiver, wrapper.broadcastReceiver)
        assertEquals(android.Manifest.permission.DUMP, wrapper.broadcastPermission)
    }

    @Test
    @Config(sdk = [34])
    fun `From API 33 the receiver is exported for adb and still behind the DUMP permission`() {
        debuggable(true)
        DebugCommands.register(app, ACTION) {}
        val wrapper = registered().single()
        assertEquals(android.Manifest.permission.DUMP, wrapper.broadcastPermission)
        assertEquals(Context.RECEIVER_EXPORTED, wrapper.flags and Context.RECEIVER_EXPORTED)
    }

    /** crash-resilience, design decision 13: `DEBUG_DECODE_CRASH` is forwarded to the decoder, in debug builds only. */
    @Test
    fun `The decoder crash exists only in a debug build, behind the DUMP permission, and reaches the decoder`() {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        debuggable(false)
        assertTrue(FrameService.registerDebugCommands(app, main) {}.isEmpty())
        assertTrue(shadowOf(app).registeredReceivers.none { it.intentFilter.hasAction(FrameService.ACTION_DEBUG_DECODE_CRASH) })

        debuggable(true)
        val forwarded = java.util.concurrent.CountDownLatch(1)
        assertEquals(2, FrameService.registerDebugCommands(app, main) { forwarded.countDown() }.size)
        val wrapper = shadowOf(app).registeredReceivers.single { it.intentFilter.hasAction(FrameService.ACTION_DEBUG_DECODE_CRASH) }
        assertEquals(android.Manifest.permission.DUMP, wrapper.broadcastPermission)
        wrapper.broadcastReceiver.onReceive(app, Intent(FrameService.ACTION_DEBUG_DECODE_CRASH))
        assertTrue(forwarded.await(5, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test
    fun `Only a debug decoder accepts the crash request`() {
        val engine = RemoteMediaEngine(OneDecoder(DecoderBinder(NoEngine, debuggable = false)))
        assertTrue(runCatching { engine.crashNextRender() }.exceptionOrNull() is java.io.IOException)
        RemoteMediaEngine(OneDecoder(DecoderBinder(NoEngine, debuggable = true))).crashNextRender()
    }

    private class OneDecoder(
        val binder: android.os.IBinder,
    ) : DecoderConnector {
        override fun connect(timeoutMs: Long) = binder

        override fun disconnect() {}

        override fun kill(pid: Int) {}
    }

    private object NoEngine : io.github.tycho991.openframe.core.platform.MediaEngine {
        override fun probe(file: java.io.File) = throw UnsupportedOperationException()

        override fun readMetadata(file: java.io.File) = throw UnsupportedOperationException()

        override fun render(
            source: java.io.File,
            target: java.io.File,
            width: Int,
            height: Int,
            region: io.github.tycho991.openframe.core.platform.Region,
            quality: Int,
        ) = throw UnsupportedOperationException()
    }

    private companion object {
        const val ACTION = "io.github.tycho991.openframe.TEST_COMMAND"
    }
}
