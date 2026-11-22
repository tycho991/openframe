package io.github.tycho991.openframe.android

import android.content.Context
import android.net.ConnectivityManager
import io.github.tycho991.openframe.core.FrameCore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Spec `kiosk-host` › Network is optional (design decision 3 of `android-platform-fixes`): the service registers the
 * unfiltered callback for the rebind and the link's LAN callback, and leaves none behind, however often `START_STICKY`
 * brings it back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FrameServiceNetworkTest {
    private val app = RuntimeEnvironment.getApplication() as FrameApp
    private val connectivity get() = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var core: FrameCore? = null

    @After
    fun stop() {
        core?.let { runBlocking { it.stop() } }
    }

    @Test
    fun `The service registers two callbacks and leaves none behind`() {
        shadowOf(connectivity).setStrictUnregistration(true)
        val light = TestCore.create(File(app.cacheDir, "service-core-${System.nanoTime()}"))
        core = light
        app.coreFactory = { light }
        repeat(2) {
            val service = Robolectric.buildService(FrameService::class.java).create()
            assertEquals(2, shadowOf(connectivity).networkCallbacks.size)
            service.destroy()
            assertEquals(0, shadowOf(connectivity).networkCallbacks.size)
        }
    }
}
