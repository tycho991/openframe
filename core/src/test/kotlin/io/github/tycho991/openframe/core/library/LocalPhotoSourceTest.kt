package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.SkipReason
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

/** Spec `photo-library`: Formate, lokaler Fotoordner, stabile IDs, Metadaten, Alben. */
class LocalPhotoSourceTest {
    @TempDir
    lateinit var dir: File

    private val log = AppLog(TestClock())
    private val engine = JvmMediaEngine(supportsHeic = false)
    private val photos get() = File(dir, "photos")
    private val index by lazy {
        PhotoIndexStore(JsonStore(File(dir, "index.json"), StoredIndex.serializer(), TestScope(), log)).also { it.load() }
    }
    private val source by lazy { LocalPhotoSource(photos, engine, index, log) }

    private fun scan() = runBlocking { source.scan() }

    private fun paths() =
        index.library.value.photos.values
            .map { it.path }
            .sorted()

    @Test
    fun `Gemischter Ordner`() {
        Fixtures.copy("landscape.jpg", File(photos, "a.jpg"))
        Fixtures.copy("image.png", File(photos, "b.png"))
        Fixtures.copy("notiz.txt", File(photos, "notiz.txt"))
        File(photos, "c.mp4").writeBytes(ByteArray(100))
        scan()
        assertEquals(listOf("a.jpg", "b.png"), paths())
        assertTrue(
            index.library.value.skipped
                .isEmpty(),
            "Dateien ohne Bild-Endung werden ignoriert, nicht übersprungen",
        )
    }

    @Test
    fun `Alle Formate`() {
        for (name in listOf("landscape.jpg", "image.png", "image.webp", "anim.gif")) Fixtures.copy(name, File(photos, name))
        scan()
        assertEquals(4, index.library.value.photos.size)
    }

    @Test
    fun `HEIC auf altem Geraet`() {
        Fixtures.copy("photo.heic", File(photos, "urlaub/photo.heic"))
        Fixtures.copy("broken.jpg", File(photos, "kaputt.jpg"))
        scan()
        assertEquals(emptyList<String>(), paths())
        val skipped =
            index.library.value.skipped
                .associate { it.path to it.reason }
        assertEquals(mapOf("urlaub/photo.heic" to SkipReason.UNSUPPORTED, "kaputt.jpg" to SkipReason.CORRUPT), skipped)
    }

    @Test
    fun `Neuscan ohne Aenderungen`() {
        Fixtures.copy("landscape.jpg", File(photos, "a.jpg"))
        Fixtures.copy("photo.heic", File(photos, "b.heic"))
        scan()
        val before = index.library.value
        val probes = engine.probes.get()
        val result = scan()
        assertEquals(ScanResult(0, 0, 0, 0), result)
        assertEquals(probes, engine.probes.get(), "Keine Bilddatei geöffnet, auch nicht die übersprungene")
        assertTrue(before === index.library.value, "Index unverändert")
    }

    @Test
    fun `Metadaten`() {
        Fixtures.copy("rotated6.jpg", File(photos, "r.jpg"))
        val noExif = Fixtures.copy("rotated8.jpg", File(photos, "n.jpg")).apply { setLastModified(1_700_000_000_000) }
        scan()
        val r =
            index.library.value.photos
                .getValue(PhotoEntry.idFor("local", "r.jpg"))
        assertEquals(300 to 400, r.width to r.height)
        assertEquals(Instant.parse("2023-01-02T08:00:00Z").toEpochMilli(), r.takenAtMs)
        val n =
            index.library.value.photos
                .getValue(PhotoEntry.idFor("local", "n.jpg"))
        assertEquals(noExif.lastModified(), n.takenAtMs, "ohne EXIF-Datum gilt das Dateidatum")
        assertEquals(noExif.length(), n.fileSize)
    }

    @Test
    fun `Datei ersetzt`() {
        val file = Fixtures.copy("landscape.jpg", File(photos, "urlaub/1.jpg"))
        scan()
        val id = PhotoEntry.idFor("local", "urlaub/1.jpg")
        index.edit { it.put(it.get(id)!!.copy(hidden = true)) }
        val before =
            index.library.value.photos
                .getValue(id)
        Fixtures.copy("rotated6.jpg", file)
        file.setLastModified(before.modifiedMs + 5000)
        scan()
        val after =
            index.library.value.photos
                .getValue(id)
        assertEquals(id, after.id)
        assertEquals(before.version + 1, after.version)
        assertEquals(true, after.hidden, "Das Ausblenden bleibt erhalten")
        assertEquals(300 to 400, after.width to after.height)
    }

    @Test
    fun `Datei entfernt`() {
        val a = Fixtures.copy("landscape.jpg", File(photos, "a.jpg"))
        val heic = Fixtures.copy("photo.heic", File(photos, "b.heic"))
        scan()
        a.delete()
        heic.delete()
        assertEquals(ScanResult(0, 0, 1, 0), scan())
        assertEquals(emptyList<String>(), paths())
        assertTrue(
            index.library.value.skipped
                .isEmpty(),
        )
    }

    @Test
    fun `Album zuordnen`() {
        Fixtures.copy("landscape.jpg", File(photos, "urlaub/2025/strand.jpg"))
        Fixtures.copy("landscape.jpg", File(photos, "lose.jpg"))
        scan()
        val albums =
            index.library.value.photos.values
                .associate { it.path to it.album }
        assertEquals(mapOf("urlaub/2025/strand.jpg" to "urlaub", "lose.jpg" to null), albums)
    }

    @Test
    fun `Versteckte Dateien und Ordner werden ignoriert`() {
        Fixtures.copy("landscape.jpg", File(photos, ".thumbnails/x.jpg"))
        Fixtures.copy("landscape.jpg", File(photos, ".hidden.jpg"))
        Fixtures.copy("landscape.jpg", File(photos, "ok.jpg"))
        scan()
        assertEquals(listOf("ok.jpg"), paths())
    }

    @Test
    fun `Uebersprungene Datei wird nach Aenderung erneut versucht`() {
        val file = Fixtures.copy("broken.jpg", File(photos, "x.jpg"))
        scan()
        assertEquals(1, index.library.value.skipped.size)
        Fixtures.copy("landscape.jpg", file)
        file.setLastModified(file.lastModified() + 5000)
        scan()
        assertEquals(listOf("x.jpg"), paths())
        assertTrue(
            index.library.value.skipped
                .isEmpty(),
        )
        assertNull(
            index.library.value.skipped
                .firstOrNull(),
        )
        assertNotEquals(0, index.library.value.version)
    }
}
