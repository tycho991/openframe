package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.testing.FakeDisplayPower
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.TestClock
import io.github.tycho991.openframe.core.testing.TestFrame
import io.github.tycho991.openframe.core.testing.waitFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration

/**
 * Spec `photo-library`: Local scan while the display is off; Local photo folder › Photo copied over USB (design decision
 * 7 of `library-performance`). Short intervals stand in for 15 seconds and 5 minutes; [TestClock] drives the 15-second
 * guard of a client's fetch.
 */
class ScanCadenceTest {
    @TempDir
    lateinit var dir: File

    private val clock = TestClock()
    private val power = FakeDisplayPower()

    private fun frame(night: Duration) =
        TestFrame(dir, scanInterval = Duration.ofMillis(200), nightScanInterval = night, clock = clock, displayPower = power)

    private fun TestFrame.indexed(path: String) =
        core.library.index.library.value.photos
            .containsKey(PhotoEntry.idFor("local", path))

    private fun TestFrame.displayOff() {
        assertEquals(200, send("PUT", "/api/v1/display", """{"on":false}""").statusCode())
        waitFor(message = "the display off") { core.slideshow.resting.value }
        // A scan that begins with the display off: the wait after it is the long one.
        val scans = core.scans.get()
        core.requestScan()
        waitFor(message = "a scan after the display went off") { core.scans.get() >= scans + 1 }
        Thread.sleep(300)
    }

    private fun copy(
        frame: TestFrame,
        path: String,
    ) = Fixtures.copy("landscape.jpg", File(frame.photos, path))

    @Test
    fun `Photo copied at night`() {
        frame(night = Duration.ofSeconds(3)).use { frame ->
            frame.displayOff()
            copy(frame, "night.jpg")
            Thread.sleep(1_000)
            assertFalse(frame.indexed("night.jpg"), "not within several day intervals")
            waitFor(5_000, "the night interval") { frame.indexed("night.jpg") }
        }
    }

    @Test
    fun `Admin interface and display at night`() {
        frame(night = Duration.ofHours(1)).use { frame ->
            frame.displayOff()
            copy(frame, "a.jpg")
            Thread.sleep(600)
            assertFalse(frame.indexed("a.jpg"))

            // GET /state is polled by clients and does not scan.
            clock.advance(Duration.ofSeconds(16))
            assertEquals(200, frame.get("/api/v1/state").statusCode())
            Thread.sleep(500)
            assertFalse(frame.indexed("a.jpg"), "no scan for GET /state")

            // GET /photos scans; the client finds the photo with its next fetch.
            assertEquals(200, frame.get("/api/v1/photos").statusCode())
            waitFor(2_000, "the scan of a client's fetch") { frame.indexed("a.jpg") }
            assertTrue(frame.get("/api/v1/photos").body().contains("\"a.jpg\""))

            // Two fetches within 15 seconds lead to one scan.
            clock.advance(Duration.ofSeconds(16))
            val scans = frame.core.scans.get()
            assertEquals(200, frame.get("/api/v1/albums").statusCode())
            assertEquals(200, frame.get("/api/v1/skipped-files").statusCode())
            waitFor(message = "one scan") { frame.core.scans.get() == scans + 1 }
            Thread.sleep(300)
            assertEquals(scans + 1, frame.core.scans.get(), "only one")

            // The display going on scans at once.
            copy(frame, "b.jpg")
            assertEquals(200, frame.send("PUT", "/api/v1/display", """{"on":true}""").statusCode())
            waitFor(2_000, "the scan when the display goes on") { frame.indexed("b.jpg") }
        }
    }
}
