package io.github.tycho991.openframe.core

import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.RenderSize
import io.github.tycho991.openframe.core.platform.AssetSource
import io.github.tycho991.openframe.core.platform.MediaStoreNotifier
import io.github.tycho991.openframe.core.slideshow.SlideState
import io.github.tycho991.openframe.core.testing.FakeDisplayPower
import io.github.tycho991.openframe.core.testing.FakePlatform
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import io.github.tycho991.openframe.core.testing.TestClock
import io.github.tycho991.openframe.core.testing.TestFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

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
    fun `Admin address is read from the network only every few seconds`() {
        val clock = TestClock()
        val platform = FakePlatform()
        val core =
            FrameCore(
                FrameDirs(File(dir, "state"), File(dir, "cache"), File(dir, "photos")),
                platform,
                JvmMediaEngine(),
                AssetSource { null },
                MediaStoreNotifier.None,
                clock = clock,
            )
        assertEquals("http://192.168.100.215:8080/", core.adminUrl())
        platform.ips = listOf("10.0.0.5")
        assertEquals("http://192.168.100.215:8080/", core.adminUrl())
        clock.advance(java.time.Duration.ofSeconds(6))
        assertEquals("http://10.0.0.5:8080/", core.adminUrl())
    }

    @Test
    fun `Empty state without photos`() {
        TestFrame(dir).use { frame ->
            waitFor { frame.core.slideshow.state.value == SlideState.Empty }
        }
    }

    @Test
    fun `The slideshow rests while the schedule has the display off`() {
        // Wednesday afternoon, then the clock moves to the night.
        val clock = TestClock(Instant.parse("2026-10-21T15:00:00Z"))
        val power = FakeDisplayPower()
        TestFrame(dir, clock = clock, displayPower = power).use { frame ->
            assertEquals(200, frame.send("PATCH", "/api/v1/settings", """{"displaySchedule":{"enabled":true}}""").statusCode())
            assertFalse(frame.core.slideshow.resting.value)
            clock.instant = Instant.parse("2026-10-21T23:00:00Z")
            // Any event makes the controller look at the clock, not only its tick.
            frame.core.display.userActivity()
            waitFor { !power.screenOn }
            assertEquals(listOf("off"), power.calls)
            waitFor { frame.core.slideshow.resting.value }
            power.powerButton()
            waitFor { !frame.core.slideshow.resting.value }
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
