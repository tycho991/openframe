package io.github.tycho991.openframe.core.sources

import io.github.tycho991.openframe.core.library.Crop
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.PhotoIndexStore
import io.github.tycho991.openframe.core.library.ScanResult
import io.github.tycho991.openframe.core.library.StoredIndex
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.ImageInfo
import io.github.tycho991.openframe.core.platform.MediaEngine
import io.github.tycho991.openframe.core.platform.SkipReason
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.store.StoreJson
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Spec `remote-sources`: Sync of a folder, No repeated downloads, Vanished photos, A failed sync loses nothing, Storage of
 * the originals, Several sources, Remove a source.
 */
class RemoteSourceTest {
    @TempDir
    lateinit var dir: File

    private val log = AppLog(TestClock())
    private val engine = JvmMediaEngine(supportsHeic = false)
    private val remote = FakeRemoteFolder()
    private val kind = FakeKind { remote }
    private val removedPhotos = ArrayList<PhotoEntry>()
    private var freeBytes = Long.MAX_VALUE / 2

    private val configs: SourceConfigStore by lazy {
        SourceConfigStore(JsonStore(File(dir, "sources.json"), StoredSources.serializer(), TestScope(), log)).also {
            it.load()
            it.add(FakeKind.source("nextcloud"))
        }
    }
    private val index by lazy {
        PhotoIndexStore(JsonStore(File(dir, "index.json"), StoredIndex.serializer(), TestScope(), log)).also { it.load() }
    }
    private val originals by lazy { OriginalsStore(File(dir, "originals"), freeBytes = { freeBytes }) }
    private val source by lazy {
        RemoteSource("nextcloud", { configs.get("nextcloud") }, kind, index, engine, originals, {
            removedPhotos +=
                it
        }, log)
    }

    private fun sync(): ScanResult = runBlocking { source.scan() }

    private fun photos() =
        index.library.value.photos.values
            .filter { it.source == "nextcloud" }
            .associateBy { it.path }

    private fun jpg(name: String = "landscape.jpg") = Fixtures.file(name).readBytes()

    @Test
    fun `A new photo is downloaded, indexed and keeps its album`() {
        remote.put("holiday/beach.jpg", jpg(), "e1")
        remote.put("top.png", Fixtures.file("image.png").readBytes(), "e2")
        assertEquals(ScanResult(2, 0, 0, 0), sync())
        val beach = photos().getValue("holiday/beach.jpg")
        assertEquals("holiday", beach.album)
        assertEquals("e1", beach.etag)
        assertEquals(PhotoEntry.idFor("nextcloud", "holiday/beach.jpg"), beach.id)
        assertNull(photos().getValue("top.png").album)
        assertTrue(originals.has(beach))
        assertTrue(beach.width > 0 && beach.height > 0)
    }

    /** Spec `remote-sources`: Sync of a folder › Nested subfolders as sub-albums. */
    @Test
    fun `Nested subfolders as sub-albums`() {
        remote.put("holiday/2024/may/beach.jpg", jpg(), "e1")
        sync()
        assertEquals("holiday/2024/may", photos().getValue("holiday/2024/may/beach.jpg").album)
        assertEquals(
            listOf("holiday", "holiday/2024", "holiday/2024/may"),
            index.library.value.albums.keys
                .toList(),
        )
    }

    @Test
    fun `Videos and other files are ignored`() {
        remote.put("clip.mp4", ByteArray(100))
        remote.put("note.txt", ByteArray(10))
        remote.put("a.jpg", jpg(), "e1")
        assertEquals(ScanResult(1, 0, 0, 0), sync())
        assertEquals(listOf("a.jpg"), remote.downloads)
    }

    @Test
    fun `Nothing changed means no download and the same index`() {
        remote.put("a.jpg", jpg(), "e1")
        sync()
        val version = index.library.value.version
        remote.downloads.clear()
        assertEquals(ScanResult(0, 0, 0, 0), sync())
        assertEquals(emptyList<String>(), remote.downloads)
        assertEquals(version, index.library.value.version)
    }

    @Test
    fun `A photo replaced on the server keeps its ID and hidden state and gets a new version`() {
        remote.put("a.jpg", jpg(), "e1")
        sync()
        val first = photos().getValue("a.jpg")
        index.edit { it.put(first.copy(hidden = true, comment = "Grandma's 80th birthday")) }
        remote.put("a.jpg", Fixtures.file("rotated6.jpg").readBytes(), "e2")
        assertEquals(ScanResult(0, 1, 0, 0), sync())
        val second = photos().getValue("a.jpg")
        assertEquals(first.id, second.id)
        assertTrue(second.hidden)
        assertEquals("Grandma's 80th birthday", second.comment, "Spec photo-library: Comment when the file changes › File replaced")
        assertEquals(first.version + 1, second.version)
        assertEquals("e2", second.etag)
    }

    @Test
    fun `A section and hidden state saved while the sync downloads the new version are kept`() {
        remote.put("a.jpg", jpg(), "e1")
        sync()
        val id = photos().getValue("a.jpg").id
        remote.put("a.jpg", jpg(), "e2")
        // The user saves a section and hides the photo while the new version is being checked.
        val racing =
            object : MediaEngine by engine {
                override fun probe(file: File): ImageInfo {
                    index.edit { it.put(it.get(id)!!.copy(crop = Crop(0.2, 0.8, 2.0), hidden = true)) }
                    return engine.probe(file)
                }
            }
        runBlocking { RemoteSource("nextcloud", { configs.get("nextcloud") }, kind, index, racing, originals, {}, log).scan() }
        val photo = photos().getValue("a.jpg")
        assertEquals(2, photo.version)
        assertEquals(Crop(0.2, 0.8, 2.0), photo.crop)
        assertTrue(photo.hidden)
    }

    @Test
    fun `A section is kept for a file of the same aspect ratio and dropped for another`() {
        // Spec `photo-library`, "Section when the file changes", for a photo of a remote source.
        remote.put("a.jpg", jpg(), "e1")
        sync()
        val crop = Crop(0.2, 0.8, 2.0)
        index.edit { it.put(photos().getValue("a.jpg").copy(crop = crop)) }
        remote.put("a.jpg", jpg(), "e2")
        sync()
        assertEquals(2, photos().getValue("a.jpg").version)
        assertEquals(crop, photos().getValue("a.jpg").crop, "Edited photo of the same size")
        remote.put("a.jpg", Fixtures.file("rotated6.jpg").readBytes(), "e3")
        sync()
        assertEquals(3, photos().getValue("a.jpg").version)
        assertNull(photos().getValue("a.jpg").crop, "Photo cropped elsewhere")
    }

    @Test
    fun `A photo deleted on the server is removed with its original`() {
        remote.put("a.jpg", jpg(), "e1")
        remote.put("b.jpg", jpg(), "e2")
        sync()
        val gone = photos().getValue("b.jpg")
        remote.remove("b.jpg")
        assertEquals(ScanResult(0, 0, 1, 0), sync())
        assertEquals(setOf("a.jpg"), photos().keys)
        assertFalse(originals.has(gone))
        assertEquals(listOf(gone.id), removedPhotos.map { it.id })
    }

    @Test
    fun `With removal switched off a vanished photo stays`() {
        remote.put("a.jpg", jpg(), "e1")
        sync()
        configs.patch(
            "nextcloud",
            kotlinx.serialization.json.buildJsonObject {
                put("removeVanished", kotlinx.serialization.json.JsonPrimitive(false))
            },
        )
        remote.remove("a.jpg")
        assertEquals(ScanResult(0, 0, 0, 0), sync())
        assertEquals(setOf("a.jpg"), photos().keys)
    }

    @Test
    fun `A listing that fails changes nothing`() {
        remote.put("a.jpg", jpg(), "e1")
        sync()
        remote.listFailure = SourceException(SourceException.Kind.SERVER, "The server answered with an error (HTTP 503)")
        val before = index.library.value
        val failure = assertThrows(SourceException::class.java) { sync() }
        assertEquals(SourceException.Kind.SERVER, failure.kind)
        assertEquals(before.version, index.library.value.version)
        assertEquals(setOf("a.jpg"), photos().keys)
    }

    @Test
    fun `A broken connection in the middle keeps what arrived and removes nothing`() {
        remote.put("a.jpg", jpg(), "e1")
        remote.put("old.jpg", jpg(), "e0")
        sync()
        remote.remove("old.jpg")
        remote.put("b.jpg", jpg(), "e2")
        remote.put("c.jpg", jpg(), "e3")
        remote.beforeDownload =
            { if (it.path == "c.jpg") throw SourceException(SourceException.Kind.UNREACHABLE, "The server cloud was not found") }
        val failure = assertThrows(SourceException::class.java) { sync() }
        assertEquals(SourceException.Kind.UNREACHABLE, failure.kind)
        assertEquals(setOf("a.jpg", "old.jpg", "b.jpg"), photos().keys, "b.jpg arrived, old.jpg is not removed after a failure")
    }

    @Test
    fun `A source that is gone as a whole ends the sync at once and removes nothing`() {
        remote.put("a.jpg", jpg(), "e1")
        sync()
        remote.put("b.jpg", jpg(), "e2")
        remote.put("c.jpg", jpg(), "e3")
        remote.remove("a.jpg")
        var attempts = 0
        remote.beforeDownload = {
            attempts++
            throw SourceException(SourceException.Kind.GONE, "The share on cloud.example.org was not found; it may have been withdrawn")
        }
        val failure = assertThrows(SourceException::class.java) { sync() }
        assertEquals(SourceException.Kind.GONE, failure.kind)
        assertEquals(1, attempts, "no further download is tried")
        assertEquals(setOf("a.jpg"), photos().keys, "nothing is removed after a failure")
    }

    @Test
    fun `A kind without folders lists everything even without subfolders`() {
        val flat =
            RemoteSource(
                "nextcloud",
                { configs.get("nextcloud") },
                FakeKind(listsFlat = true) { remote },
                index,
                engine,
                originals,
                {},
                log,
            )
        configs.patch(
            "nextcloud",
            kotlinx.serialization.json.buildJsonObject {
                put("includeSubfolders", kotlinx.serialization.json.JsonPrimitive(false))
            },
        )
        remote.put("album/a.jpg", jpg(), "e1")
        remote.put("top.jpg", jpg(), "e2")
        assertEquals(ScanResult(2, 0, 0, 0), runBlocking { flat.scan() })
        assertEquals(ScanResult(0, 0, 1, 0), sync(), "a kind with folders does not list the subfolder, so its photo goes")
    }

    @Test
    fun `A file that cannot be decoded is skipped and not downloaded again`() {
        remote.put("broken.jpg", Fixtures.file("broken.jpg").readBytes(), "e1")
        remote.put("heic.heic", Fixtures.file("photo.heic").readBytes(), "e2")
        remote.put("ok.jpg", jpg(), "e3")
        assertEquals(ScanResult(1, 0, 0, 2), sync())
        val skipped =
            index.library.value.skipped
                .filter { it.source == "nextcloud" }
                .associateBy { it.path }
        assertEquals(SkipReason.CORRUPT, skipped.getValue("broken.jpg").reason)
        assertEquals(SkipReason.UNSUPPORTED, skipped.getValue("heic.heic").reason)
        assertEquals(setOf("ok.jpg"), photos().keys)
        remote.downloads.clear()
        sync()
        assertEquals(emptyList<String>(), remote.downloads)
    }

    @Test
    fun `A file that vanished after the listing is not a skipped file`() {
        remote.put("a.jpg", jpg(), "e1")
        remote.beforeDownload = { remote.remove(it.path) }
        assertEquals(ScanResult(0, 0, 0, 0), sync())
        assertEquals(emptyList<Any>(), index.library.value.skipped)
    }

    @Test
    fun `Not enough free storage stops the sync before a download`() {
        remote.put("a.jpg", jpg(), "e1")
        freeBytes = 50L * 1024 * 1024
        val failure = assertThrows(SourceException::class.java) { sync() }
        assertEquals(SourceException.Kind.STORAGE, failure.kind)
        assertEquals(emptyList<String>(), remote.downloads)
    }

    @Test
    fun `A removed original comes back by a sync while the budget has room`() {
        remote.put("a.jpg", jpg(), "e1")
        sync()
        val before = photos().getValue("a.jpg")
        val libraryVersion = index.library.value.version
        originals.fileOf(before).delete()
        remote.downloads.clear()
        // Only the original comes back: the photo, its version and the library are not touched, and nothing is counted.
        assertEquals(ScanResult(0, 0, 0, 0), sync())
        assertEquals(listOf("a.jpg"), remote.downloads)
        assertTrue(originals.has(before))
        assertEquals(before, photos().getValue("a.jpg"))
        assertEquals(libraryVersion, index.library.value.version)
    }

    @Test
    fun `A new photo gets its GPS position`() {
        remote.put("siena.jpg", jpg("gps-siena.jpg"), "e1")
        remote.put("plain.jpg", jpg(), "e2")
        sync()
        val siena = photos().getValue("siena.jpg")
        assertEquals(43.3188, siena.gps!!.lat, 1e-6)
        assertEquals(PhotoEntry.METADATA_VERSION, siena.metadataVersion)
        assertNull(photos().getValue("plain.jpg").gps)
        assertEquals(PhotoEntry.METADATA_VERSION, photos().getValue("plain.jpg").metadataVersion)
    }

    /** Spec `photo-library`: Metadata of photos already in the index › Remote photo without its original. */
    @Test
    fun `Remote photo without its original`() {
        remote.put("siena.jpg", jpg("gps-siena.jpg"), "e1")
        sync()
        // As a build before the GPS position left it, and with the original removed from the frame's storage.
        val old = photos().getValue("siena.jpg").copy(gps = null, metadataVersion = 0)
        index.edit { it.put(old.copy(hidden = true)) }
        originals.fileOf(old).delete()
        assertNull(photos().getValue("siena.jpg").gps)
        remote.downloads.clear()
        // The ETag is unchanged: the photo stays the same, it only takes the position of the download over, which is
        // not counted as an update of the photo.
        assertEquals(ScanResult(0, 0, 0, 0), sync())
        assertEquals(listOf("siena.jpg"), remote.downloads)
        val after = photos().getValue("siena.jpg")
        assertEquals(43.3188, after.gps!!.lat, 1e-6)
        assertEquals(PhotoEntry.METADATA_VERSION, after.metadataVersion)
        assertEquals(old.version, after.version, "Not a new image version: nothing is rendered again")
        assertTrue(after.hidden)
        // Read once: the next download of the same ETag leaves the entry alone.
        val libraryVersion = index.library.value.version
        originals.fileOf(after).delete()
        assertEquals(ScanResult(0, 0, 0, 0), sync())
        assertEquals(libraryVersion, index.library.value.version)
    }

    @Test
    fun `A library larger than the budget is not downloaded again at every run`() {
        remote.put("a.jpg", jpg(), "e1")
        sync()
        val entry = photos().getValue("a.jpg")
        // A budget of 0 MB (not settable through the API, but a file from an older version could hold anything).
        File(dir, "tight.json").writeText(
            StoreJson.encodeToString(
                StoredSources.serializer(),
                StoredSources(sources = listOf(FakeKind.source("nextcloud").copy(originalsBudgetMb = 0))),
            ),
        )
        val tightConfigs =
            SourceConfigStore(JsonStore(File(dir, "tight.json"), StoredSources.serializer(), TestScope(), log)).also {
                it.load()
            }
        val tightSource = RemoteSource("nextcloud", { tightConfigs.get("nextcloud") }, kind, index, engine, originals, {}, log)
        originals.fileOf(entry).delete()
        remote.downloads.clear()
        runBlocking { tightSource.scan() }
        runBlocking { tightSource.scan() }
        assertEquals(emptyList<String>(), remote.downloads)
        // A rendering still gets its original, and the trim does not take it away again at once.
        val file = tightSource.original(entry)
        assertTrue(file.isFile)
        assertEquals(listOf("a.jpg"), remote.downloads)
    }

    @Test
    fun `A rendering fetches a removed original`() {
        remote.put("a.jpg", jpg(), "e1")
        sync()
        val entry = photos().getValue("a.jpg")
        originals.fileOf(entry).delete()
        remote.downloads.clear()
        assertTrue(source.original(entry).isFile)
        assertEquals(listOf("a.jpg"), remote.downloads)
        remote.downloads.clear()
        source.original(entry)
        assertEquals(emptyList<String>(), remote.downloads, "an original that is there is not fetched")
    }

    @Test
    fun `A rendering of a removed source or with a failing download fails`() {
        remote.put("a.jpg", jpg(), "e1")
        sync()
        val entry = photos().getValue("a.jpg")
        originals.fileOf(entry).delete()
        remote.beforeDownload = { throw SourceException(SourceException.Kind.UNREACHABLE, "The server cloud is not reachable") }
        assertThrows(SourceException::class.java) { source.original(entry) }
        assertFalse(originals.has(entry))
        configs.remove("nextcloud")
        assertEquals(SourceException.Kind.NOT_FOUND, assertThrows(SourceException::class.java) { source.original(entry) }.kind)
    }

    @Test
    fun `The store removes the originals unused for the longest time first`() {
        val store = OriginalsStore(File(dir, "lru"))
        val entries = (1..3).map { PhotoEntry("nextcloud-$it", "nextcloud", "p$it.jpg", 1, 1, 0, 0, 0, 1) }
        for ((i, e) in entries.withIndex()) {
            store.fileOf(e).writeBytes(ByteArray(1000))
            store.fileOf(e).setLastModified(10_000L + i * 1000L)
        }
        assertEquals(1, store.trim(2_000))
        assertFalse(store.has(entries[0]), "the oldest is gone")
        assertTrue(store.has(entries[1]) && store.has(entries[2]))
    }

    @Test
    fun `Using an original makes it the youngest`() {
        val store = OriginalsStore(File(dir, "touch"), now = { 50_000 })
        val entries = (1..2).map { PhotoEntry("nextcloud-$it", "nextcloud", "p$it.jpg", 1, 1, 0, 0, 0, 1) }
        for ((i, e) in entries.withIndex()) {
            store.fileOf(e).writeBytes(ByteArray(1000))
            store.fileOf(e).setLastModified(10_000L + i * 1000L)
        }
        store.touch(entries[0])
        store.trim(1_000)
        assertTrue(store.has(entries[0]))
        assertFalse(store.has(entries[1]))
    }

    @Test
    fun `The file that was just fetched is not trimmed away`() {
        val store = OriginalsStore(File(dir, "keep"))
        val entry = PhotoEntry("nextcloud-1", "nextcloud", "p.jpg", 1, 1, 0, 0, 0, 1)
        store.fileOf(entry).writeBytes(ByteArray(1000))
        assertEquals(0, store.trim(0, keep = store.fileOf(entry)))
        assertTrue(store.has(entry))
    }

    @Test
    fun `Leftovers of a cut off download are removed at the start`() {
        val folder = File(dir, "leftover").apply { mkdirs() }
        File(folder, "nextcloud-1.jpg.part").writeBytes(ByteArray(10))
        File(folder, "nextcloud-2.jpg").writeBytes(ByteArray(10))
        OriginalsStore(folder)
        assertEquals(listOf("nextcloud-2.jpg"), folder.list().orEmpty().toList())
    }

    @Test
    fun `A removed source does nothing, and a sync that ran across the removal applies nothing`() {
        remote.put("a.jpg", jpg(), "e1")
        remote.beforeDownload = { configs.remove("nextcloud") }
        assertEquals(ScanResult(0, 0, 0, 0), sync())
        assertEquals(emptyMap<String, PhotoEntry>(), photos())
        remote.beforeDownload = {}
        remote.downloads.clear()
        assertEquals(ScanResult(0, 0, 0, 0), sync())
        assertEquals(emptyList<String>(), remote.downloads)
    }

    @Test
    fun `A switched off source does nothing`() {
        remote.put("a.jpg", jpg(), "e1")
        configs.patch(
            "nextcloud",
            kotlinx.serialization.json.buildJsonObject { put("enabled", kotlinx.serialization.json.JsonPrimitive(false)) },
        )
        assertEquals(ScanResult(0, 0, 0, 0), sync())
        assertEquals(emptyList<String>(), remote.downloads)
    }
}
