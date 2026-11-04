package io.github.tycho991.openframe.core

import io.github.tycho991.openframe.core.http.ServerState
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.RenderCache
import io.github.tycho991.openframe.core.library.RenderSize
import io.github.tycho991.openframe.core.log.LogLevel
import io.github.tycho991.openframe.core.platform.AssetSource
import io.github.tycho991.openframe.core.platform.MediaStoreNotifier
import io.github.tycho991.openframe.core.platform.Region
import io.github.tycho991.openframe.core.slideshow.SlideState
import io.github.tycho991.openframe.core.testing.FakeDisplayPower
import io.github.tycho991.openframe.core.testing.FakeNetworkLink
import io.github.tycho991.openframe.core.testing.FakePlatform
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import io.github.tycho991.openframe.core.testing.TestClock
import io.github.tycho991.openframe.core.testing.TestFrame
import io.github.tycho991.openframe.core.testing.waitFor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.time.Instant

/** Interplay of library, cache and slideshow in the real core. */
class FrameCoreTest {
    @TempDir
    lateinit var dir: File

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
        assertEquals("http://192.168.100.215:8080/", FrameCore.adminUrl("192.168.100.215", 8080))
        assertEquals("http://192.168.100.215/", FrameCore.adminUrl("192.168.100.215", 80))
        // A running server reports the port it is bound to; before the start there is none.
        val unstarted =
            FrameCore(
                FrameDirs(File(dir, "state"), File(dir, "cache"), File(dir, "photos")),
                FakePlatform(),
                JvmMediaEngine(),
                AssetSource { null },
                MediaStoreNotifier.None,
            )
        assertNull(unstarted.adminUrl())
        TestFrame(dir).use { frame ->
            assertEquals("http://192.168.100.215:${frame.core.server.boundPort}/", frame.core.adminUrl())
        }
        TestFrame(File(dir, "offline"), platform = FakePlatform(ips = emptyList())).use { frame ->
            assertNull(frame.core.adminUrl())
            assertTrue(frame.core.server.state.value is ServerState.Running)
        }
    }

    @Test
    fun `Admin address is read from the network only every few seconds`() {
        val clock = TestClock()
        val platform = FakePlatform()
        TestFrame(dir, platform = platform, clock = clock).use { frame ->
            val port = frame.core.server.boundPort
            assertEquals("http://192.168.100.215:$port/", frame.core.adminUrl())
            platform.ips = listOf("10.0.0.5")
            assertEquals("http://192.168.100.215:$port/", frame.core.adminUrl())
            clock.advance(java.time.Duration.ofSeconds(6))
            assertEquals("http://10.0.0.5:$port/", frame.core.adminUrl())
        }
    }

    /** Design decision 14 of `app-auto-update`: renderings of an older format go at the start, not in the constructor. */
    @Test
    fun `Renderings of an older format are deleted at the start`() {
        val old = File(dir, "cache/renders").apply { mkdirs() }
        File(old, "abc-v1-p1024x600_0_0_10000_10000.jpg").writeText("old")
        Fixtures.copy("landscape.jpg", File(File(dir, "photos"), "a.jpg"))
        val core =
            FrameCore(
                FrameDirs(File(dir, "state"), File(dir, "cache"), File(dir, "photos")),
                FakePlatform(),
                JvmMediaEngine(),
                AssetSource { null },
                MediaStoreNotifier.None,
                port = 0,
                bindLoopback = true,
            )
        try {
            assertTrue(old.isDirectory, "the constructor deletes nothing")
            runBlocking { core.start() }
            waitFor(message = "old folder deleted") { !old.exists() }
            waitFor { core.slideshow.state.value is SlideState.Showing }
            val folder = File(dir, "cache/${RenderCache.FOLDER}")
            assertEquals("renders-r1", folder.name)
            waitFor(message = "rendered into the new folder") { folder.list().orEmpty().any { it.endsWith(".jpg") } }
        } finally {
            runBlocking { core.stop() }
        }
    }

    /** Spec `kiosk-host`: Server port unavailable › Port taken at start. */
    @Test
    fun `A taken port does not stop the start, and the server binds once it is free`() {
        val holder = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port = holder.localPort
        Fixtures.copy("landscape.jpg", File(File(dir, "photos"), "a.jpg"))
        val core =
            FrameCore(
                FrameDirs(File(dir, "state"), File(dir, "cache"), File(dir, "photos")),
                FakePlatform(),
                JvmMediaEngine(),
                AssetSource { null },
                MediaStoreNotifier.None,
                port = port,
                bindLoopback = true,
                serverRetryMs = 200,
            )
        try {
            runBlocking { core.start() }
            assertTrue(core.ready.value)
            assertTrue(core.server.state.value is ServerState.Failed)
            assertNull(core.adminUrl())
            assertTrue(core.log.recent(1000).any { it.level == LogLevel.ERROR && "could not bind" in it.message })
            waitFor { core.slideshow.state.value is SlideState.Showing }

            // Neither a change of local-only mode nor of the network throws while the port is taken.
            core.setLocalOnly(true)
            core.setLocalOnly(false)
            repeat(20) { core.onNetworkChanged() }
            holder.close()
            // Requests queued meanwhile may rebind once more; between two binds the port is briefly not held.
            waitFor { core.server.boundPort == port }
            assertEquals("http://192.168.100.215:$port/", core.adminUrl())

            // Many triggers at once end with exactly one bound server on the port.
            repeat(20) {
                core.onNetworkChanged()
                core.setLocalOnly(it % 2 == 0)
            }
            core.setLocalOnly(false)
            waitFor { core.server.boundPort == port }
            Thread.sleep(300)
            waitFor { core.server.boundPort == port }
            assertEquals(ServerState.Running(port), core.server.state.value)
            assertThrows(IOException::class.java) { ServerSocket(port, 50, InetAddress.getLoopbackAddress()).close() }
        } finally {
            holder.close()
            runBlocking { core.stop() }
        }
    }

    @Test
    fun `A test frame binds to loopback only and leaves the setting local only off`() {
        TestFrame(dir).use { frame ->
            val core = frame.core

            fun binds() =
                core.log
                    .recent(1000)
                    .filter { it.message.startsWith("Server on ") }
                    .map { it.message }
            assertTrue(core.server.localOnly)
            assertFalse(core.settings.localOnly.value)
            // A network change and both positions of the setting bind the server anew, each time to loopback.
            core.onNetworkChanged()
            waitFor { binds().size >= 2 }
            core.setLocalOnly(true)
            waitFor { binds().size >= 3 }
            core.setLocalOnly(false)
            waitFor { binds().size >= 4 }
            assertTrue(binds().all { it.startsWith("Server on 127.0.0.1:") }, binds().toString())
            assertTrue(core.server.localOnly)
            assertFalse(core.settings.localOnly.value)
            val port = core.server.boundPort!!
            Socket().use { it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000) }
            for (address in ownLanAddresses()) {
                assertThrows(IOException::class.java, { Socket().use { it.connect(InetSocketAddress(address, port), 1000) } }, "$address")
            }
        }
    }

    /** This machine's own IPv4 addresses apart from loopback; empty on a machine without a network. */
    private fun ownLanAddresses(): List<InetAddress> =
        NetworkInterface
            .getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()

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
    fun `The slideshow keeps resting during a dark wake`() {
        val clock = TestClock(Instant.parse("2026-10-21T15:00:00Z"))
        val power = FakeDisplayPower()
        val network = FakeNetworkLink()
        TestFrame(dir, clock = clock, displayPower = power, networkLink = network).use { frame ->
            assertEquals(200, frame.send("PATCH", "/api/v1/settings", """{"displaySchedule":{"enabled":true}}""").statusCode())
            clock.instant = Instant.parse("2026-10-21T23:00:00Z")
            frame.core.display.userActivity()
            waitFor { frame.core.slideshow.resting.value }
            network.drop()
            waitFor { network.requests == 1 }
            // Ten minutes later; a touch on the dark display makes the controller look at the time at once.
            clock.advance(Duration.ofMinutes(10))
            frame.core.display.userActivity()
            waitFor { frame.core.display.dark.value }
            assertTrue(power.screenOn)
            assertTrue(frame.core.slideshow.resting.value)
            val display = frame.get("/api/v1/display")
            assertEquals(200, display.statusCode())
            assertTrue(display.body().contains("\"on\":false"), display.body())
            assertTrue(display.body().contains("\"cause\":\"schedule\""), display.body())
            network.restore()
            waitFor { !frame.core.display.dark.value && !power.screenOn }
            assertTrue(frame.core.slideshow.resting.value)
            assertEquals(listOf("off", "on", "off"), power.calls)
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

    /** A scan still running at stop() logged into the state folder afterwards, and `@TempDir` could not delete it. */
    @Test
    fun `Stop waits for a running scan`() {
        val frame = TestFrame(dir)
        // The scan loop runs every 200 ms in tests: while photos arrive, one of its scans is running at close().
        for (i in 1..250) Fixtures.copy("landscape.jpg", File(frame.photos, "p%03d.jpg".format(i)))
        frame.close()
        val atStop = files()
        Thread.sleep(500)
        assertEquals(atStop, files())
    }

    private fun files() = dir.walk().filter { it.isFile }.associate { it.relativeTo(dir).path to (it.length() to it.lastModified()) }
}
