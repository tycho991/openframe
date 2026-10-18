package io.github.tycho991.openframe.core

import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.RenderSize
import io.github.tycho991.openframe.core.slideshow.SlideState
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.TestFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Zusammenspiel von Bibliothek, Cache und Slideshow im echten Kern. */
class FrameCoreTest {
    @TempDir
    lateinit var dir: File

    private fun waitFor(
        timeoutMs: Long = 5000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(condition(), "Bedingung nicht erfüllt")
    }

    @Test
    fun `Kaltstart mit leerem Cache zeigt das erste Foto`() {
        val photos = File(dir, "photos")
        for (i in 1..4) Fixtures.copy("landscape.jpg", File(photos, "p$i.jpg"))
        TestFrame(dir).use { frame ->
            assertTrue(frame.core.ready.value)
            waitFor { frame.core.slideshow.state.value is SlideState.Showing }
            val shown = frame.core.slideshow.state.value as SlideState.Showing
            assertTrue(shown.file.exists())
            // Die nächsten Fotos werden vorab gerendert.
            waitFor {
                frame.core.visiblePhotos.value
                    .count { frame.core.cache.peek(it, RenderSize.DISPLAY) != null } >= 3
            }
        }
    }

    @Test
    fun `Leerzustand ohne Fotos`() {
        TestFrame(dir).use { frame ->
            waitFor { frame.core.slideshow.state.value == SlideState.Empty }
        }
    }

    @Test
    fun `Einstellungen und Bibliothek ueberstehen einen Neustart`() {
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
