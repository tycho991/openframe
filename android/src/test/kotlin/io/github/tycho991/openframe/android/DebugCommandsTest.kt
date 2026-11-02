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

    private companion object {
        const val ACTION = "io.github.tycho991.openframe.TEST_COMMAND"
    }
}
