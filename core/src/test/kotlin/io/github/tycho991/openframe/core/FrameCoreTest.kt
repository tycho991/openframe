package io.github.tycho991.openframe.core

import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.RenderSize
import io.github.tycho991.openframe.core.platform.AssetSource
import io.github.tycho991.openframe.core.platform.MediaStoreNotifier
import io.github.tycho991.openframe.core.platform.Region
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

    /** Spec `slideshow-display`: Caption changes without a photo change. */
    @Test
    fun `Comment, place and language reach the caption without a photo change`() {
        Fixtures.copy("gps-siena.jpg", File(File(dir, "photos"), "holiday/2024/Tuscany/siena.jpg"))
        TestFrame(dir).use { frame ->
            waitFor { frame.core.slideshow.state.value is SlideState.Showing }
            val shown = frame.core.slideshow.state.value as SlideState.Showing
            waitFor {
                frame.core.caption.value
                    ?.seq == shown.seq
            }
            assertNull(
                frame.core.caption.value!!
                    .text,
                "nothing to show yet",
            )

            // Scenario "Comment edited while the photo is shown".
            frame.send("PATCH", "/api/v1/photos/${shown.photo.id}", """{"comment":"Grandma's 80th birthday"}""")
            waitFor {
                frame.core.caption.value
                    ?.text
                    ?.line == "Grandma's 80th birthday"
            }
            // Scenario "Place switched on".
            frame.send("PATCH", "/api/v1/settings", """{"caption":{"place":true,"albumTitle":true},"language":"en"}""")
            waitFor {
                frame.core.caption.value
                    ?.text
                    ?.place == "Siena, Italy"
            }
            assertEquals(
                "Tuscany · Grandma's 80th birthday",
                frame.core.caption.value!!
                    .text!!
                    .line,
            )
            // A changed language on the frame.
            frame.send("PATCH", "/api/v1/settings", """{"language":"de"}""")
            waitFor {
                frame.core.caption.value
                    ?.text
                    ?.place == "Siena, Italien"
            }
            assertEquals(
                shown.seq,
                frame.core.caption.value!!
                    .seq,
                "the same slide",
            )
            assertEquals(shown.seq, (frame.core.slideshow.state.value as SlideState.Showing).seq, "the photo did not change")
        }
    }

    @Test
    fun `The slideshow takes up a new section and a new fill mode at the next change`() {
        val photos = File(dir, "photos")
        for (i in 1..3) Fixtures.copy("landscape.jpg", File(photos, "p$i.jpg"))
        TestFrame(dir).use { frame ->
            // With shuffle the photos ahead end with the pass; in date order every other photo is ahead.
            assertEquals(200, frame.send("PATCH", "/api/v1/settings", """{"order":"chronological"}""").statusCode())
            waitFor { frame.core.slideshow.state.value is SlideState.Showing }

            fun shown() = frame.core.slideshow.state.value as SlideState.Showing

            fun current(id: String) =
                frame.core.library.index.library.value.photos
                    .getValue(id)

            // Every photo gets a section at the top left, so whichever comes next has one.
            for (photo in frame.core.visiblePhotos.value) {
                assertEquals(200, frame.send("PATCH", "/api/v1/photos/${photo.id}", """{"crop":{"x":0,"y":0,"zoom":1}}""").statusCode())
            }
            val withSection = { e: PhotoEntry -> frame.core.cache.target(e, RenderSize.DISPLAY) }

            // The photos after the one on screen are rendered ahead after the change of the library, before the next change.
            fun upcomingRendered() =
                frame.core.visiblePhotos.value
                    .filter { it.id != shown().photo.id }
                    .all { frame.core.cache.peek(it, RenderSize.DISPLAY) != null }
            waitFor { upcomingRendered() }
            val before = shown().seq
            frame.core.slideshow.next()
            waitFor { shown().seq > before }
            assertEquals(withSection(current(shown().photo.id)).name, shown().file.name)
            // 400×300 on 1024×600 at zoom 1: the full width and the top 0.78 of the height.
            assertEquals(0.0, withSection(current(shown().photo.id)).region.y, 1e-9)
            assertTrue(Region(0.0, 0.0, 1.0, 0.78125) in frame.engine.regions)

            // `fit` without a section shows the whole photo.
            for (photo in frame.core.visiblePhotos.value) frame.send("PATCH", "/api/v1/photos/${photo.id}", """{"crop":null}""")
            assertEquals(200, frame.send("PATCH", "/api/v1/settings", """{"fillMode":"fit"}""").statusCode())
            waitFor {
                frame.core.visiblePhotos.value
                    .all {
                        frame.core.cache
                            .target(it, RenderSize.DISPLAY)
                            .region == Region.FULL
                    } &&
                    upcomingRendered()
            }
            val again = shown().seq
            frame.core.slideshow.next()
            waitFor { shown().seq > again }
            assertEquals(
                frame.core.cache
                    .target(current(shown().photo.id), RenderSize.DISPLAY)
                    .name,
                shown().file.name,
            )
            assertEquals(Region.FULL, frame.engine.regions.last())
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
