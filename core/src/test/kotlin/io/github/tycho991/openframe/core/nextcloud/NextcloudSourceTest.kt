package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.PhotoIndexStore
import io.github.tycho991.openframe.core.library.ScanResult
import io.github.tycho991.openframe.core.library.StoredIndex
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.SkipReason
import io.github.tycho991.openframe.core.store.JsonStore
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

/** Spec `nextcloud-source`: Sync of a folder, No repeated downloads, Vanished photos, A failed sync loses nothing, Storage of the originals. */
class NextcloudSourceTest {
    @TempDir
    lateinit var dir: File

    private val log = AppLog(TestClock())
    private val engine = JvmMediaEngine(supportsHeic = false)
    private val clients = FakeClients()
    private val remote get() = clients.remote
    private val removedPhotos = ArrayList<PhotoEntry>()
    private var freeBytes = Long.MAX_VALUE / 2

    private val configs by lazy {
        NextcloudConfigStore(JsonStore(File(dir, "nextcloud.json"), StoredNextcloud.serializer(), TestScope(), log)).also {
            it.load()
            it.connect("https://cloud.example.org", "anna", "Anna", "app-pw-1234")
        }
    }
    private val index by lazy {
        PhotoIndexStore(JsonStore(File(dir, "index.json"), StoredIndex.serializer(), TestScope(), log)).also { it.load() }
    }
    private val originals by lazy { OriginalsStore(File(dir, "originals"), freeBytes = { freeBytes }) }
    private val source by lazy { NextcloudSource(configs, clients, index, engine, originals, { removedPhotos += it }, log) }

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
        index.edit { it.put(first.copy(hidden = true)) }
        remote.put("a.jpg", Fixtures.file("rotated6.jpg").readBytes(), "e2")
        assertEquals(ScanResult(0, 1, 0, 0), sync())
        val second = photos().getValue("a.jpg")
        assertEquals(first.id, second.id)
        assertTrue(second.hidden)
        assertEquals(first.version + 1, second.version)
        assertEquals("e2", second.etag)
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
        configs.patch(kotlinx.serialization.json.buildJsonObject { put("removeVanished", kotlinx.serialization.json.JsonPrimitive(false)) })
        remote.remove("a.jpg")
        assertEquals(ScanResult(0, 0, 0, 0), sync())
        assertEquals(setOf("a.jpg"), photos().keys)
    }

    @Test
    fun `A listing that fails changes nothing`() {
        remote.put("a.jpg", jpg(), "e1")
        sync()
        remote.listFailure = NextcloudException(NextcloudException.Kind.SERVER, "The server answered with an error (HTTP 503)")
        val before = index.library.value
        val failure = assertThrows(NextcloudException::class.java) { sync() }
        assertEquals(NextcloudException.Kind.SERVER, failure.kind)
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
            { if (it.path == "c.jpg") throw NextcloudException(NextcloudException.Kind.UNREACHABLE, "The server cloud was not found") }
        val failure = assertThrows(NextcloudException::class.java) { sync() }
        assertEquals(NextcloudException.Kind.UNREACHABLE, failure.kind)
        assertEquals(setOf("a.jpg", "old.jpg", "b.jpg"), photos().keys, "b.jpg arrived, old.jpg is not removed after a failure")
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
        val failure = assertThrows(NextcloudException::class.java) { sync() }
        assertEquals(NextcloudException.Kind.STORAGE, failure.kind)
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
    fun `A library larger than the budget is not downloaded again at every run`() {
        remote.put("a.jpg", jpg(), "e1")
        sync()
        val entry = photos().getValue("a.jpg")
        // A budget of 0 MB (not settable through the API, but a file from an older version could hold anything).
        val tight =
            StoredNextcloud(
                serverUrl = "https://cloud.example.org",
                userId = "anna",
                loginName = "Anna",
                appPassword = "app-pw",
                originalsBudgetMb = 0,
            )
        File(dir, "tight.json").writeText(
            kotlinx.serialization.json.Json
                .encodeToString(StoredNextcloud.serializer(), tight),
        )
        val tightConfigs =
            NextcloudConfigStore(JsonStore(File(dir, "tight.json"), StoredNextcloud.serializer(), TestScope(), log)).also {
                it.load()
            }
        val tightSource = NextcloudSource(tightConfigs, clients, index, engine, originals, {}, log)
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
    fun `A rendering without a connection or with a failing download fails`() {
        remote.put("a.jpg", jpg(), "e1")
        sync()
        val entry = photos().getValue("a.jpg")
        originals.fileOf(entry).delete()
        remote.beforeDownload = { throw NextcloudException(NextcloudException.Kind.UNREACHABLE, "The server cloud is not reachable") }
        assertThrows(NextcloudException::class.java) { source.original(entry) }
        assertFalse(originals.has(entry))
        configs.disconnect()
        assertEquals(NextcloudException.Kind.UNAUTHORIZED, assertThrows(NextcloudException::class.java) { source.original(entry) }.kind)
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
    fun `Disconnecting removes the photos and originals of the source and revokes the password`() {
        remote.put("a.jpg", jpg(), "e1")
        sync()
        val local = PhotoEntry(PhotoEntry.idFor("local", "mine.jpg"), "local", "mine.jpg", 1, 1, 0, 1, 1, 1)
        index.edit { it.put(local) }
        val remoteEntry = photos().getValue("a.jpg")
        source.disconnect()
        assertEquals(
            setOf("mine.jpg"),
            index.library.value.photos.values
                .map { it.path }
                .toSet(),
        )
        assertFalse(originals.has(remoteEntry))
        assertFalse(configs.view().connected)
        assertEquals(1, (clients.login as FakeLoginFlow).revoked)
        assertNotNull(removedPhotos.firstOrNull { it.path == "a.jpg" })
    }

    @Test
    fun `A source that is not connected does nothing`() {
        configs.disconnect()
        assertEquals(ScanResult(0, 0, 0, 0), sync())
        assertEquals(emptyList<String>(), remote.downloads)
    }
}
