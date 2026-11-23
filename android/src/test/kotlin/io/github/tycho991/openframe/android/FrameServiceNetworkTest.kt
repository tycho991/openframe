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
import org.robolectric.shadows.ShadowNetwork
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
        val wifi = ShadowNetwork.newInstance(100)
        repeat(2) {
            val service = Robolectric.buildService(FrameService::class.java).create()
            val callbacks = shadowOf(connectivity).networkCallbacks
            assertEquals(2, callbacks.size)
            // The rebind's callback; a restarted service hears of the network it is on again.
            callbacks.single { it.javaClass.name.startsWith(FrameService::class.java.name) }.onAvailable(wifi)
            service.destroy()
            assertEquals(0, shadowOf(connectivity).networkCallbacks.size)
        }
        val available = light.log.recent(100).count { it.message == "Network available" }
        assertEquals("the set of known networks lives as long as the process", 1, available)
    }
}
