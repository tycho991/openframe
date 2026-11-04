package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.ImageInfo
import io.github.tycho991.openframe.core.platform.MediaEngine
import io.github.tycho991.openframe.core.platform.SkipReason
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

/** Spec `photo-library`: Formats, local photo folder, stable IDs, metadata, albums. */
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
    fun `Mixed folder`() {
        Fixtures.copy("landscape.jpg", File(photos, "a.jpg"))
        Fixtures.copy("image.png", File(photos, "b.png"))
        Fixtures.copy("note.txt", File(photos, "note.txt"))
        File(photos, "c.mp4").writeBytes(ByteArray(100))
        scan()
        assertEquals(listOf("a.jpg", "b.png"), paths())
        assertTrue(
            index.library.value.skipped
                .isEmpty(),
            "Files without an image extension are ignored, not skipped",
        )
    }

    @Test
    fun `A photo that enters the index while the folder is listed stays`() {
        Fixtures.copy("landscape.jpg", File(photos, "a.jpg"))
        scan()
        // An upload that finishes while the scan lists the folder: its file is missing from the listing, but its entry
        // is in the index before the scan applies its result.
        val late = File(photos, "late.jpg")
        val racing =
            LocalPhotoSource(photos, engine, index, log) { root ->
                LocalPhotoSource.listImageFiles(root).also {
                    Fixtures.copy("landscape.jpg", late)
                    source.add(late)
                }
            }
        val result = runBlocking { racing.scan() }
        assertEquals(listOf("a.jpg", "late.jpg"), paths())
        assertEquals(0, result.removed)
    }

    @Test
    fun `A photo uploaded under the name of one deleted while the folder is listed stays`() {
        val a = File(photos, "a.jpg")
        Fixtures.copy("landscape.jpg", a)
        scan()
        // The old a.jpg is deleted before the listing reaches it, a new a.jpg is uploaded right after: same path and
        // ID, but a new entry, not the one the scan knew.
        val racing =
            LocalPhotoSource(photos, engine, index, log) { root ->
                a.delete()
                index.edit { it.remove(PhotoEntry.idFor(LocalPhotoSource.SOURCE_ID, "a.jpg")) }
                LocalPhotoSource.listImageFiles(root).also {
                    Fixtures.copy("landscape.jpg", a)
                    source.add(a)
                }
            }
        val result = runBlocking { racing.scan() }
        assertEquals(listOf("a.jpg"), paths())
        assertEquals(0, result.removed)
    }

    @Test
    fun `All formats`() {
        for (name in listOf("landscape.jpg", "image.png", "image.webp", "anim.gif")) Fixtures.copy(name, File(photos, name))
        scan()
        assertEquals(4, index.library.value.photos.size)
    }

    @Test
    fun `HEIC on an old device`() {
        Fixtures.copy("photo.heic", File(photos, "holiday/photo.heic"))
        Fixtures.copy("broken.jpg", File(photos, "damaged.jpg"))
        scan()
        assertEquals(emptyList<String>(), paths())
        val skipped =
            index.library.value.skipped
                .associate { it.path to it.reason }
        assertEquals(mapOf("holiday/photo.heic" to SkipReason.UNSUPPORTED, "damaged.jpg" to SkipReason.CORRUPT), skipped)
    }

    @Test
    fun `Rescan without changes`() {
        Fixtures.copy("landscape.jpg", File(photos, "a.jpg"))
        Fixtures.copy("photo.heic", File(photos, "b.heic"))
        scan()
        val before = index.library.value
        val probes = engine.probes.get()
        val result = scan()
        assertEquals(ScanResult(0, 0, 0, 0), result)
        assertEquals(probes, engine.probes.get(), "No image file opened, not even the skipped one")
        assertEquals(0, engine.metadataReads.get(), "Not even for its metadata")
        assertTrue(before === index.library.value, "Index unchanged")
    }

    @Test
    fun `Metadata`() {
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
        assertEquals(noExif.lastModified(), n.takenAtMs, "without an EXIF date the file date applies")
        assertEquals(noExif.length(), n.fileSize)
    }

    @Test
    fun `Metadata - photo with GPS position`() {
        Fixtures.copy("gps-siena.jpg", File(photos, "tuscany/siena.jpg"))
        scan()
        val entry =
            index.library.value.photos
                .getValue(PhotoEntry.idFor("local", "tuscany/siena.jpg"))
        assertEquals(43.3188, entry.gps!!.lat, 1e-6)
        assertEquals(11.3308, entry.gps!!.lon, 1e-6)
        assertEquals(PhotoEntry.METADATA_VERSION, entry.metadataVersion)
    }

    @Test
    fun `Metadata - photo without GPS position`() {
        Fixtures.copy("landscape.jpg", File(photos, "a.jpg"))
        Fixtures.copy("gps-zero.jpg", File(photos, "zero.jpg"))
        scan()
        for (path in listOf("a.jpg", "zero.jpg")) {
            val entry =
                index.library.value.photos
                    .getValue(PhotoEntry.idFor("local", path))
            assertNull(entry.gps, path)
            assertEquals(PhotoEntry.METADATA_VERSION, entry.metadataVersion, "$path counts as read")
        }
        val probes = engine.probes.get()
        scan()
        assertEquals(probes, engine.probes.get(), "A later rescan does not open the file again")
        assertEquals(0, engine.metadataReads.get())
    }

    @Test
    fun `An upload keeps the position of its check`() {
        val file = Fixtures.copy("gps-siena.jpg", File(photos, "upload.jpg"))
        val info = engine.probe(file)
        val probes = engine.probes.get()
        val entry = source.add(file, info)
        assertEquals(info.gps, entry.gps)
        assertEquals(PhotoEntry.METADATA_VERSION, entry.metadataVersion)
        assertEquals(probes, engine.probes.get())
    }

    @Test
    fun `File replaced`() {
        val file = Fixtures.copy("landscape.jpg", File(photos, "holiday/1.jpg"))
        scan()
        val id = PhotoEntry.idFor("local", "holiday/1.jpg")
        index.edit { it.put(it.get(id)!!.copy(hidden = true, comment = "Grandma's 80th birthday")) }
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
        assertEquals(true, after.hidden, "The hidden state is kept")
        assertEquals("Grandma's 80th birthday", after.comment, "The comment is kept")
        assertEquals(300 to 400, after.width to after.height)
    }

    /** Spec `photo-library`: Comment when the file changes › File moved. */
    @Test
    fun `File moved`() {
        val file = Fixtures.copy("landscape.jpg", File(photos, "holiday/1.jpg"))
        scan()
        val id = PhotoEntry.idFor("local", "holiday/1.jpg")
        index.edit { it.put(it.get(id)!!.copy(comment = "Grandma's 80th birthday")) }
        File(photos, "family").mkdirs()
        file.renameTo(File(photos, "family/1.jpg"))
        scan()
        val photos = index.library.value.photos
        assertNull(photos[id])
        assertNull(photos.getValue(PhotoEntry.idFor("local", "family/1.jpg")).comment)
    }

    @Test
    fun `Section when the file changes`() {
        val file = Fixtures.copy("landscape.jpg", File(photos, "a.jpg"))
        scan()
        val id = PhotoEntry.idFor("local", "a.jpg")
        val crop = Crop(0.3, 0.4, 1.5)
        index.edit { it.put(it.get(id)!!.copy(crop = crop)) }
        val photo = {
            index.library.value.photos
                .getValue(id)
        }

        // Scenario "Edited photo of the same size".
        Fixtures.copy("landscape.jpg", file)
        file.setLastModified(photo().modifiedMs + 5000)
        scan()
        assertEquals(2, photo().version)
        assertEquals(crop, photo().crop, "The section is kept")

        // Scenario "Photo cropped elsewhere": 400×300 becomes 300×400.
        Fixtures.copy("rotated6.jpg", file)
        file.setLastModified(photo().modifiedMs + 5000)
        scan()
        assertEquals(3, photo().version)
        assertNull(photo().crop, "Another aspect ratio drops the section")
    }

    @Test
    fun `Aspect ratio within one percent keeps the section`() {
        val previous = PhotoEntry("p", "local", "p.jpg", 4000, 3000, 0, 1, 1, 1, crop = Crop(0.5, 0.5, 2.0))
        assertEquals(previous.crop, Crop.carriedOver(previous, 4000, 3000))
        assertEquals(previous.crop, Crop.carriedOver(previous, 2000, 1500), "Scaled down, same aspect ratio")
        assertEquals(previous.crop, Crop.carriedOver(previous, 4000, 3025), "0.8 % off")
        assertNull(Crop.carriedOver(previous, 4000, 3040), "1.3 % off")
        assertNull(Crop.carriedOver(previous, 3000, 3000))
        assertNull(Crop.carriedOver(previous.copy(crop = null), 4000, 3000))
        assertNull(Crop.carriedOver(null, 4000, 3000))
    }

    @Test
    fun `An index without sections loads with none, and one is stored only when set`() =
        runBlocking {
            Fixtures.copy("landscape.jpg", File(photos, "a.jpg"))
            Fixtures.copy("landscape.jpg", File(photos, "b.jpg"))
            scan()
            index.flush()
            assertTrue(!File(dir, "index.json").readText().contains("crop"))
            val id = PhotoEntry.idFor("local", "a.jpg")
            index.edit { it.put(it.get(id)!!.copy(crop = Crop(0.1, 0.2, 3.0))) }
            index.flush()
            val restarted =
                PhotoIndexStore(
                    JsonStore(File(dir, "index.json"), StoredIndex.serializer(), TestScope(), log),
                ).also { it.load() }
            val photos = restarted.library.value.photos
            assertEquals(Crop(0.1, 0.2, 3.0), photos.getValue(id).crop)
            assertNull(photos.getValue(PhotoEntry.idFor("local", "b.jpg")).crop)
            assertEquals(1, Regex("\"crop\"").findAll(File(dir, "index.json").readText()).count())
        }

    @Test
    fun `File removed`() {
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
    fun `Photo uploaded during a scan survives it`() {
        Fixtures.copy("landscape.jpg", File(photos, "a.jpg"))
        val uploaded = File(photos, "upload.jpg")
        var added = false
        // The upload arrives while the scan is probing a file, after the file list was taken.
        val racing =
            object : MediaEngine by engine {
                override fun probe(file: File): ImageInfo {
                    if (!added) {
                        added = true
                        Fixtures.copy("image.png", uploaded)
                        racingSource.add(uploaded)
                    }
                    return engine.probe(file)
                }
            }
        racingSource = LocalPhotoSource(photos, racing, index, log)
        runBlocking { racingSource.scan() }
        assertEquals(listOf("a.jpg", "upload.jpg"), paths())
        assertEquals(
            1,
            index.library.value.photos.values
                .first { it.path == "upload.jpg" }
                .version,
        )
    }

    private lateinit var racingSource: LocalPhotoSource

    @Test
    fun `A section and hidden state saved while the scan reads the changed file are kept`() {
        val file = Fixtures.copy("landscape.jpg", File(photos, "a.jpg"))
        scan()
        val id = PhotoEntry.idFor("local", "a.jpg")
        Fixtures.copy("landscape.jpg", file)
        file.setLastModified(file.lastModified() + 5000)
        // The user saves a section and hides the photo while the scan probes its new version.
        val racing =
            object : MediaEngine by engine {
                override fun probe(file: File): ImageInfo {
                    index.edit { it.put(it.get(id)!!.copy(crop = Crop(0.2, 0.3, 1.5), hidden = true)) }
                    return engine.probe(file)
                }
            }
        runBlocking { LocalPhotoSource(photos, racing, index, log).scan() }
        val photo =
            index.library.value.photos
                .getValue(id)
        assertEquals(2, photo.version)
        assertEquals(Crop(0.2, 0.3, 1.5), photo.crop)
        assertTrue(photo.hidden)
    }

    @Test
    fun `Assign album`() {
        Fixtures.copy("landscape.jpg", File(photos, "holiday/2025/beach.jpg"))
        Fixtures.copy("landscape.jpg", File(photos, "holiday/2025/may/sea.jpg"))
        Fixtures.copy("landscape.jpg", File(photos, "holiday/top.jpg"))
        Fixtures.copy("landscape.jpg", File(photos, "loose.jpg"))
        scan()
        val albums =
            index.library.value.photos.values
                .associate { it.path to it.album }
        assertEquals(
            mapOf(
                "holiday/2025/beach.jpg" to "holiday/2025",
                "holiday/2025/may/sea.jpg" to "holiday/2025/may",
                "holiday/top.jpg" to "holiday",
                "loose.jpg" to null,
            ),
            albums,
        )
        assertEquals(
            listOf("holiday", "holiday/2025", "holiday/2025/may"),
            index.library.value.albums.keys
                .toList(),
        )
    }

    @Test
    fun `Hidden files and folders are ignored`() {
        Fixtures.copy("landscape.jpg", File(photos, ".thumbnails/x.jpg"))
        Fixtures.copy("landscape.jpg", File(photos, ".hidden.jpg"))
        Fixtures.copy("landscape.jpg", File(photos, "ok.jpg"))
        scan()
        assertEquals(listOf("ok.jpg"), paths())
    }

    @Test
    fun `Skipped file is tried again after a change`() {
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

    private var mounted = true
    private val guarded by lazy { LocalPhotoSource(photos, engine, index, log, mounted = { mounted }) }

    private fun guardedScan() = runBlocking { guarded.scan() }

    /** A photo with comment, section and hidden state, scanned into the index. */
    private fun keptPhoto(): PhotoEntry {
        Fixtures.copy("landscape.jpg", File(photos, "a.jpg"))
        guardedScan()
        val id = PhotoEntry.idFor("local", "a.jpg")
        index.edit { it.put(it.get(id)!!.copy(comment = "Grandma's garden", crop = Crop(0.5, 0.2, 1.5), hidden = true)) }
        return index.library.value.photos[id]!!
    }

    /** Spec `photo-library`: Photo folder kept › Storage mounted late after a restart. */
    @Test
    fun `While the storage is not mounted the scan removes and creates nothing`() {
        val kept = keptPhoto()
        photos.deleteRecursively()
        mounted = false
        assertEquals(ScanResult(0, 0, 0, 0), guardedScan())
        assertEquals(kept, index.library.value.photos[kept.id], "comment, section and hidden state kept")
        assertFalse(photos.exists(), "no folder created on the unmounted storage")
        assertFalse(guarded.available())
    }

    @Test
    fun `A photo folder that cannot be listed removes nothing`() {
        val kept = keptPhoto()
        photos.deleteRecursively()
        photos.writeText("a file in the place of the folder")
        assertEquals(ScanResult(0, 0, 0, 0), guardedScan())
        assertEquals(kept, index.library.value.photos[kept.id])
        assertFalse(guarded.available())
    }

    @Test
    fun `An empty folder on mounted storage still removes its entries`() {
        val kept = keptPhoto()
        File(photos, "a.jpg").delete()
        assertEquals(1, guardedScan().removed)
        assertNull(index.library.value.photos[kept.id])
    }

    /** Spec `photo-library`: Photo folder kept › Folder deleted over USB. */
    @Test
    fun `A folder deleted on mounted storage is created again and its entries removed`() {
        val kept = keptPhoto()
        photos.deleteRecursively()
        assertEquals(1, guardedScan().removed)
        assertTrue(photos.isDirectory)
        assertNull(index.library.value.photos[kept.id])
    }
}
