package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.settings.Orientation
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.imageio.ImageIO

/** Spec `photo-library`: Gerenderte Bildgrößen, Speicherplatz begrenzen. */
class RenderCacheTest {
    @TempDir
    lateinit var dir: File

    private val engine = JvmMediaEngine()
    private val photos get() = File(dir, "photos")
    private var budget = 10L * 1024 * 1024
    private var screen = PixelSize(320, 200)

    private fun cache() = RenderCache(File(dir, "cache"), engine, { File(photos, it.path) }, { screen }, { budget }, AppLog(TestClock()))

    private fun entry(
        name: String,
        fixture: String = "landscape.jpg",
        width: Int = 400,
        height: Int = 300,
        version: Int = 1,
    ): PhotoEntry {
        Fixtures.copy(fixture, File(photos, name))
        return PhotoEntry(PhotoEntry.idFor("local", name), "local", name, width, height, 0, 1, 1, version)
    }

    @Test
    fun `Bildschirmdeckende Anzeigegroesse`() {
        val landscape = RenderSizes.screen(1920, 1200, Orientation.LANDSCAPE)
        assertEquals(PixelSize(1920, 1200), landscape)
        assertEquals(PixelSize(1200, 1920), RenderSizes.screen(1920, 1200, Orientation.PORTRAIT))
        assertEquals(PixelSize(1200, 1920), RenderSizes.screen(1200, 1920, Orientation.PORTRAIT))
        // Szenario „Hochformatfoto auf Querformat-Rahmen“.
        assertEquals(PixelSize(1920, 2560), RenderSizes.display(3000, 4000, landscape))
        assertEquals(PixelSize(1920, 1280), RenderSizes.display(6000, 4000, landscape))
        // Panorama 8:1 an der Obergrenze: höchstens das 2,5-Fache der Pixelzahl des Bildschirms.
        val pano = RenderSizes.display(16000, 2000, landscape)
        assertTrue(pano.width.toLong() * pano.height <= (2.5 * 1920 * 1200).toLong() + pano.width + pano.height)
        assertEquals(8.0, pano.width.toDouble() / pano.height, 0.01)
        // Kleiner als der Bildschirm: nicht hochskalieren.
        assertEquals(PixelSize(800, 600), RenderSizes.display(800, 600, landscape))
        assertEquals(PixelSize(400, 300), RenderSizes.thumb(4000, 3000))
        assertEquals(PixelSize(300, 400), RenderSizes.thumb(3000, 4000))
        assertEquals(PixelSize(200, 100), RenderSizes.thumb(200, 100))
    }

    @Test
    fun `Vorschaubild abrufen`() =
        runBlocking {
            val e = entry("a.jpg")
            val file = cache().get(e, RenderSize.THUMB)
            val img = ImageIO.read(file)
            assertEquals(400 to 300, img.width to img.height)
        }

    @Test
    fun `Anzeigegroesse beachtet EXIF und Bildschirm`() =
        runBlocking {
            // rotated6: gespeichert 400×300, angezeigt 300×400.
            val e = entry("r.jpg", "rotated6.jpg", 300, 400)
            screen = PixelSize(200, 120)
            val img = ImageIO.read(cache().get(e, RenderSize.DISPLAY))
            assertEquals(200 to 267, img.width to img.height)
        }

    @Test
    fun `Wiederholter Abruf rendert nicht neu`() =
        runBlocking {
            val c = cache()
            val e = entry("a.jpg")
            c.get(e, RenderSize.THUMB)
            c.get(e, RenderSize.THUMB)
            assertEquals(1, engine.renders.get())
            // Auch ein neuer Cache nach Neustart findet die Datei.
            cache().get(e, RenderSize.THUMB)
            assertEquals(1, engine.renders.get())
        }

    @Test
    fun `Gleichzeitige Anfragen rendern einmal`() =
        runBlocking {
            val c = cache()
            val e = entry("a.jpg")
            (1..10).map { async { c.get(e, RenderSize.DISPLAY) } }.awaitAll()
            assertEquals(1, engine.renders.get())
        }

    @Test
    fun `Neue Bildversion ersetzt alte Dateien`() =
        runBlocking {
            val c = cache()
            val v1 = entry("a.jpg")
            c.get(v1, RenderSize.THUMB)
            c.get(v1, RenderSize.DISPLAY)
            val v2 = v1.copy(version = 2)
            c.get(v2, RenderSize.THUMB)
            val names = File(dir, "cache").list()!!.toList()
            assertEquals(listOf("${v1.id}-v2-400x300.jpg"), names)
        }

    @Test
    fun `Budget ueberschritten`() =
        runBlocking {
            val c = cache()
            val entries = (1..6).map { entry("p$it.jpg") }
            entries.forEach { c.get(it, RenderSize.THUMB) }
            val one = File(dir, "cache").listFiles()!!.first().length()
            budget = one * 3
            c.get(entries[0], RenderSize.THUMB) // zuletzt genutzt
            c.trim()
            assertTrue(c.totalBytes() <= budget)
            assertNotNull(c.peek(entries[0], RenderSize.THUMB), "zuletzt genutzter Eintrag bleibt")
            assertNull(c.peek(entries[1], RenderSize.THUMB), "am längsten nicht genutzter Eintrag ist entfernt")
            assertEquals(6, photos.listFiles()!!.size, "Originale bleiben erhalten")
        }

    @Test
    fun `Gerade gerendertes Bild bleibt auch bei winzigem Budget`() =
        runBlocking {
            budget = 1
            val f = cache().get(entry("a.jpg"), RenderSize.THUMB)
            assertTrue(f.exists())
        }

    @Test
    fun `Entfernen und alte Anzeigegroesse`() =
        runBlocking {
            val c = cache()
            val e = entry("a.jpg")
            c.get(e, RenderSize.DISPLAY)
            screen = PixelSize(200, 320)
            assertNull(c.peek(e, RenderSize.DISPLAY), "neues Format, noch nicht gerendert")
            assertNotNull(c.peekAnyDisplay(e), "die alte Anzeigegröße steht bis dahin zur Verfügung")
            c.remove(e.id)
            assertEquals(0, File(dir, "cache").list()!!.size)
        }

    @Test
    fun `Fehler beim Rendern`() {
        val e = entry("b.jpg", "broken.jpg")
        val result = runCatching { runBlocking { cache().get(e, RenderSize.THUMB) } }
        assertTrue(result.isFailure)
    }
}
