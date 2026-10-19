package io.github.tycho991.openframe.core

import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.RenderSize
import io.github.tycho991.openframe.core.platform.AssetSource
import io.github.tycho991.openframe.core.platform.MediaStoreNotifier
import io.github.tycho991.openframe.core.slideshow.SlideState
import io.github.tycho991.openframe.core.testing.FakePlatform
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import io.github.tycho991.openframe.core.testing.TestFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Interplay of library, cache and slideshow in the real core. */
class FrameCoreTest {
    @TempDir
    lateinit var dir: File

    private fun waitFor(
        timeoutMs: Long = 5000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(condition(), "Condition not met")
    }

    @Test
    fun `Cold start with an empty cache shows the first photo`() {
        val photos = File(dir, "photos")
        for (i in 1..4) Fixtures.copy("landscape.jpg", File(photos, "p$i.jpg"))
        TestFrame(dir).use { frame ->
            assertTrue(frame.core.ready.value)
            waitFor { frame.core.slideshow.state.value is SlideState.Showing }
            val shown = frame.core.slideshow.state.value as SlideState.Showing
            assertTrue(shown.file.exists())
            // The next photos are rendered ahead.
            waitFor {
                frame.core.visiblePhotos.value
                    .count { frame.core.cache.peek(it, RenderSize.DISPLAY) != null } >= 3
            }
        }
    }

    @Test
    fun `Admin address as the frame shows it`() {
        fun core(
            port: Int,
            platform: FakePlatform = FakePlatform(),
        ) = FrameCore(
            FrameDirs(File(dir, "state"), File(dir, "cache"), File(dir, "photos")),
            platform,
            JvmMediaEngine(),
            AssetSource { null },
            MediaStoreNotifier.None,
            port = port,
        )
        assertEquals("http://192.168.100.215:8080/", core(8080).adminUrl())
        assertEquals("http://192.168.100.215/", core(80).adminUrl())
        assertNull(core(8080, FakePlatform(ips = emptyList())).adminUrl())
        // A running server reports the port it is bound to.
        TestFrame(dir).use { frame ->
            assertEquals("http://192.168.100.215:${frame.core.server.boundPort}/", frame.core.adminUrl())
        }
    }

    @Test
    fun `Empty state without photos`() {
        TestFrame(dir).use { frame ->
            waitFor { frame.core.slideshow.state.value == SlideState.Empty }
        }
    }

    @Test
    fun `Settings and library survive a restart`() {
        TestFrame(dir).use { frame ->
            Fixtures.copy("landscape.jpg", File(frame.photos, "a.jpg"))
            frame.scan()
            frame.send("PATCH", "/api/v1/settings", """{"intervalSeconds":60}""")
            frame.send("PATCH", "/api/v1/photos/${PhotoEntry.idFor("local", "a.jpg")}", """{"hidden":true}""")
        }
        TestFrame(dir).use { frame ->
            assertEquals(60, frame.core.settings.settings.value.intervalSeconds)
            val entry = frame.core.library.index.library.value.photos[PhotoEntry.idFor("local", "a.jpg")]
            assertNotNull(entry)
            assertTrue(entry!!.hidden)
        }
    }
}
