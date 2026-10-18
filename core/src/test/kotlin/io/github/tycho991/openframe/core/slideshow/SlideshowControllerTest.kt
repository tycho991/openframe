@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.tycho991.openframe.core.slideshow

import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.RenderPriority
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.settings.Order
import io.github.tycho991.openframe.core.settings.Settings
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Spec `slideshow-display` mit virtueller Zeit. */
class SlideshowControllerTest {
    private fun photo(n: Int): PhotoEntry {
        val path = "p%02d.jpg".format(n)
        return PhotoEntry(PhotoEntry.idFor("local", path), "local", path, 4, 3, n * 1000L, 1, 1, 1)
    }

    private class FakeImages : DisplayImages {
        val readyIds = mutableSetOf<String>()
        val broken = mutableSetOf<String>()
        val prefetched = mutableListOf<Pair<String, RenderPriority>>()
        val pending = mutableMapOf<String, CompletableDeferred<File>>()
        val demanded = mutableListOf<String>()

        override fun ready(entry: PhotoEntry): File? = if (entry.id in readyIds) File(entry.path) else null

        override suspend fun render(entry: PhotoEntry): File {
            demanded += entry.id
            if (entry.id in broken) throw IllegalStateException("kaputt")
            return pending.getOrPut(entry.id) { CompletableDeferred() }.await().also { readyIds += entry.id }
        }

        override fun prefetch(
            entries: List<PhotoEntry>,
            priority: RenderPriority,
        ) {
            entries.forEach { prefetched += it.id to priority }
        }
    }

    private class Setup(
        scope: TestScope,
        photos: List<PhotoEntry>,
        allReady: Boolean = true,
        settings: Settings = Settings.defaults("x").copy(order = Order.CHRONOLOGICAL, intervalSeconds = 30),
    ) {
        val visible = MutableStateFlow(photos)
        val library = photos.map { it.id }.toMutableSet()
        val settings = MutableStateFlow(settings)
        val images = FakeImages().apply { if (allReady) readyIds += photos.map { it.id } }
        val controller =
            SlideshowController(visible, { it in library }, this.settings, images, scope.backgroundScope, AppLog(TestClock())).also {
                it.start()
                scope.runCurrent()
            }

        fun shown(): PhotoEntry? = (controller.state.value as? SlideState.Showing)?.photo
    }

    private val photos = (1..5).map(::photo)

    @Test
    fun `Wechsel nach Intervall und Umlauf`() =
        runTest {
            val s = Setup(this, photos)
            assertEquals(photos[0], s.shown())
            advanceTimeBy(29_999)
            runCurrent()
            assertEquals(photos[0], s.shown())
            advanceTimeBy(2)
            runCurrent()
            assertEquals(photos[1], s.shown(), "nach 30 Sekunden das nächste sichtbare Foto")
            advanceTimeBy(30_000 * 4L)
            runCurrent()
            assertEquals(photos[0], s.shown(), "nach dem letzten wieder das erste")
        }

    @Test
    fun `Zurueckwischen startet das Intervall neu`() =
        runTest {
            val s = Setup(this, photos)
            advanceTimeBy(30_001)
            runCurrent()
            advanceTimeBy(20_000)
            s.controller.previous()
            runCurrent()
            assertEquals(photos[0], s.shown())
            assertEquals(-1, (s.controller.state.value as SlideState.Showing).step)
            advanceTimeBy(29_000)
            runCurrent()
            assertEquals(photos[0], s.shown(), "das Intervall beginnt neu")
            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(photos[1], s.shown())
        }

    @Test
    fun `Pause`() =
        runTest {
            val s = Setup(this, photos)
            s.controller.togglePause()
            runCurrent()
            assertTrue(s.controller.paused.value)
            advanceTimeBy(10 * 60_000L)
            runCurrent()
            assertEquals(photos[0], s.shown(), "bleibt stehen, bis fortgesetzt wird")
            s.controller.togglePause()
            runCurrent()
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(photos[1], s.shown())
        }

    @Test
    fun `Intervall wirkt beim naechsten Wechsel`() =
        runTest {
            val s = Setup(this, photos)
            s.settings.value = s.settings.value.copy(intervalSeconds = 5)
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(photos[1], s.shown())
            advanceTimeBy(5_001)
            runCurrent()
            assertEquals(photos[2], s.shown())
        }

    @Test
    fun `Noch nicht gerenderte Fotos werden uebersprungen und vorab gerendert`() =
        runTest {
            val s = Setup(this, photos)
            s.images.readyIds -= photos[1].id
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(photos[2], s.shown())
            assertTrue(photos[1].id to RenderPriority.NEXT in s.images.prefetched)
        }

    @Test
    fun `Naechstes Foto ist vorbereitet`() =
        runTest {
            val s = Setup(this, photos)
            assertEquals(
                photos[1],
                s.controller.nextUp.value
                    ?.first,
            )
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(
                photos[2],
                s.controller.nextUp.value
                    ?.first,
            )
        }

    @Test
    fun `Naechste Fotos werden vorab gerendert`() =
        runTest {
            val s = Setup(this, photos)
            assertTrue(s.images.prefetched.contains(photos[1].id to RenderPriority.NEXT))
            assertTrue(s.images.prefetched.contains(photos[2].id to RenderPriority.NEXT))
            assertTrue(s.images.prefetched.contains(photos[3].id to RenderPriority.PREFETCH))
        }

    @Test
    fun `Erster Start mit leerem Cache rendert zuerst das erste Foto`() =
        runTest {
            val s = Setup(this, photos, allReady = false)
            assertEquals(SlideState.Loading, s.controller.state.value)
            assertEquals(listOf(photos[0].id), s.images.demanded, "zuerst das erste Foto der Reihenfolge")
            s.images.pending
                .getValue(photos[0].id)
                .complete(File("p01.jpg"))
            runCurrent()
            assertEquals(photos[0], s.shown())
        }

    @Test
    fun `Defekte Datei beim ersten Rendern`() =
        runTest {
            val images = FakeImages().apply { broken += photos[0].id }
            val s = Setup(this, photos, allReady = false)
            // Eigener Aufbau mit defektem erstem Foto.
            val controller =
                SlideshowController(
                    MutableStateFlow(photos),
                    { true },
                    s.settings,
                    images,
                    backgroundScope,
                    AppLog(TestClock()),
                ).also { it.start() }
            runCurrent()
            assertEquals(listOf(photos[0].id, photos[1].id), images.demanded, "nach dem Fehler das darauf folgende Foto")
            images.pending.getValue(photos[1].id).complete(File("p02.jpg"))
            runCurrent()
            assertEquals(photos[1], (controller.state.value as SlideState.Showing).photo)
        }

    @Test
    fun `Defekte Datei in der Anzeige`() =
        runTest {
            val s = Setup(this, photos)
            s.controller.reportUndecodable(photos[0])
            runCurrent()
            assertEquals(photos[1], s.shown(), "zeigt stattdessen das darauf folgende Foto")
        }

    @Test
    fun `Aktuelles Foto geloescht`() =
        runTest {
            val s = Setup(this, photos)
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(photos[1], s.shown())
            s.library -= photos[1].id
            s.visible.value = photos - photos[1]
            runCurrent()
            assertEquals(photos[2], s.shown(), "sofort zum nächsten Foto")
        }

    @Test
    fun `Album ausgeblendet wirkt beim naechsten Wechsel`() =
        runTest {
            val s = Setup(this, photos)
            s.visible.value = listOf(photos[0], photos[3])
            runCurrent()
            assertEquals(photos[0], s.shown(), "ausgeblendet, nicht gelöscht: kein sofortiger Wechsel")
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(photos[3], s.shown())
        }

    @Test
    fun `Leerzustand und neues Foto`() =
        runTest {
            val s = Setup(this, emptyList())
            assertEquals(SlideState.Empty, s.controller.state.value)
            s.images.readyIds += photos[0].id
            s.library += photos[0].id
            s.visible.value = listOf(photos[0])
            runCurrent()
            assertEquals(photos[0], s.shown())
            s.visible.value = emptyList()
            runCurrent()
            assertEquals(SlideState.Empty, s.controller.state.value)
        }
}
