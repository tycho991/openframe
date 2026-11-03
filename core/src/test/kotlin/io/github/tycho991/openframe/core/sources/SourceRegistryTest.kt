package io.github.tycho991.openframe.core.sources

import io.github.tycho991.openframe.core.http.HttpException
import io.github.tycho991.openframe.core.library.Crop
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.PhotoIndexStore
import io.github.tycho991.openframe.core.library.StoredIndex
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `remote-sources`: Several sources, Remove a source, Sync on request, Folder before first sync. */
class SourceRegistryTest {
    @TempDir
    lateinit var dir: File

    private val scope = TestScope()
    private val log = AppLog(TestClock())
    private val folders = HashMap<String, FakeRemoteFolder>()
    private val kind = FakeKind { folders.getOrPut(it.id) { FakeRemoteFolder() } }
    private val removedPhotos = ArrayList<PhotoEntry>()
    private val removedSources = ArrayList<String>()

    private val index by lazy {
        PhotoIndexStore(JsonStore(File(dir, "index.json"), StoredIndex.serializer(), TestScope(), log)).also { it.load() }
    }
    private val configs by lazy {
        SourceConfigStore(JsonStore(File(dir, "sources.json"), StoredSources.serializer(), TestScope(), log)).also { it.load() }
    }
    private val results by lazy { SyncResultStore(JsonStore(File(dir, "status.json"), StoredSyncResults.serializer(), TestScope(), log)) }
    private val registry by lazy {
        SourceRegistry(
            scope,
            configs,
            results,
            listOf(kind),
            index,
            JvmMediaEngine(),
            File(dir, "sources"),
            { removedPhotos += it },
            TestClock(),
            log,
            { removedSources += it.id },
        ).also { it.start() }
    }

    @AfterEach
    fun stop() = scope.cancel()

    private fun remote(id: String) = folders.getOrPut(id) { FakeRemoteFolder() }

    private fun sync(id: String) = runBlocking { registry.source(id)!!.scan() }

    private fun pathsOf(source: String) =
        index.library.value.photos.values
            .filter { it.source == source }
            .map { it.path }
            .toSet()

    private fun jpg() = Fixtures.file("landscape.jpg").readBytes()

    @Test
    fun `Two sources each have their photos, and the same album name is one album`() {
        registry.add(FakeKind.source("sa"))
        registry.add(FakeKind.source("sb"))
        remote("sa").put("holiday/a.jpg", jpg(), "e1")
        remote("sb").put("holiday/b.jpg", jpg(), "e2")
        sync("sa")
        sync("sb")
        assertEquals(setOf("holiday/a.jpg"), pathsOf("sa"))
        assertEquals(setOf("holiday/b.jpg"), pathsOf("sb"))
        val albums =
            index.library.value.photos.values
                .map { it.album }
                .toSet()
        assertEquals(setOf("holiday"), albums)
        assertTrue(
            index.library.value.photos.keys
                .all { it.startsWith("sa-") || it.startsWith("sb-") },
        )
    }

    @Test
    fun `Removing one of two sources leaves the other and the local photos`() {
        registry.add(FakeKind.source("sa"))
        registry.add(FakeKind.source("sb"))
        remote("sa").put("a.jpg", jpg(), "e1")
        remote("sb").put("b.jpg", jpg(), "e2")
        sync("sa")
        sync("sb")
        val local = PhotoEntry(PhotoEntry.idFor("local", "mine.jpg"), "local", "mine.jpg", 1, 1, 0, 1, 1, 1)
        index.edit { it.put(local) }
        val kept =
            index.library.value.photos.values
                .single { it.source == "sb" }
        val gone =
            index.library.value.photos.values
                .single { it.source == "sa" }
        assertTrue(registry.source("sa")!!.originals.has(gone))

        assertTrue(registry.remove("sa"))
        assertFalse(registry.remove("sa"), "a second removal of the same source does nothing")
        assertEquals(0, kind.revoked, "the server is asked afterwards, not inside the request")
        scope.runCurrent()

        assertEquals(
            setOf("mine.jpg", "b.jpg"),
            index.library.value.photos.values
                .map { it.path }
                .toSet(),
        )
        assertFalse(File(dir, "sources/sa").exists(), "the originals of the removed source are gone")
        assertTrue(registry.source("sb")!!.originals.has(kept), "the originals of the other one stay")
        assertNull(configs.get("sa"))
        assertNull(registry.source("sa"))
        assertEquals(1, kind.revoked)
        assertEquals(listOf(gone.id), removedPhotos.map { it.id })
        assertEquals(listOf("sa"), removedSources)
        assertFalse(registry.remove("nope"), "an unknown source")
    }

    @Test
    fun `At the start photos and originals of a source that is not stored any more are removed`() {
        val orphan = PhotoEntry(PhotoEntry.idFor("sgone", "a.jpg"), "sgone", "a.jpg", 1, 1, 0, 1, 1, 1)
        val local = PhotoEntry(PhotoEntry.idFor("local", "mine.jpg"), "local", "mine.jpg", 1, 1, 0, 1, 1, 1)
        index.edit {
            it.put(orphan)
            it.put(local)
            it.skip(
                io.github.tycho991.openframe.core.library.SkippedFile(
                    "sgone",
                    "b.jpg",
                    io.github.tycho991.openframe.core.platform.SkipReason.CORRUPT,
                    1,
                    1,
                ),
            )
        }
        configs.add(FakeKind.source("skept"))
        File(dir, "sources/sgone").mkdirs()
        File(dir, "sources/sgone/${orphan.id}.jpg").writeBytes(ByteArray(10))
        File(dir, "sources/skept").mkdirs()
        registry
        assertEquals(setOf(local.id), index.library.value.photos.keys)
        assertEquals(emptyList<Any>(), index.library.value.skipped)
        assertFalse(File(dir, "sources/sgone").exists())
        assertTrue(File(dir, "sources/skept").exists(), "the folder of a stored source stays")
        assertEquals(listOf(orphan.id), removedPhotos.map { it.id })
    }

    /** Spec `remote-sources`: Photos kept when the sources cannot be read. */
    @Test
    fun `An unreadable sources json keeps the remote photos with comments, sections and originals`() {
        val photo =
            PhotoEntry(PhotoEntry.idFor("skept", "a.jpg"), "skept", "a.jpg", 1, 1, 0, 1, 1, 1, hidden = true)
                .copy(comment = "Grandma's garden", crop = Crop(0.5, 0.2, 1.5))
        index.edit { it.put(photo) }
        File(dir, "sources/skept").mkdirs()
        File(dir, "sources/skept/${photo.id}.jpg").writeBytes(ByteArray(10))
        File(dir, "sources.json").writeText("{ damaged")
        val unreadable = SourceConfigStore(JsonStore(File(dir, "sources.json"), StoredSources.serializer(), TestScope(), log))
        assertEquals(SourceConfigStore.Loaded.UNREADABLE, unreadable.load())

        SourceRegistry(
            scope,
            unreadable,
            results,
            listOf(kind),
            index,
            JvmMediaEngine(),
            File(dir, "sources"),
            { removedPhotos += it },
            TestClock(),
            log,
            { removedSources += it.id },
        ).start(removeOrphans = false)

        assertEquals(photo, index.library.value.photos[photo.id])
        assertTrue(File(dir, "sources/skept/${photo.id}.jpg").isFile)
        assertEquals(emptyList<PhotoEntry>(), removedPhotos)
        assertEquals("{ damaged", File(dir, "sources.json").readText())
    }

    @Test
    fun `A share-like source syncs at once after adding, an account waits for its folder`() {
        val share = registry.add(FakeKind.source("sa"))
        assertTrue(registry.status(share.id).syncing, "the first sync shows as running")
        val account = registry.add(FakeKind.source("sb").copy(folder = ""))
        assertFalse(registry.status(account.id).syncing)
        val refused = assertThrows(HttpException::class.java) { registry.requestSync("sb") }
        assertEquals(409, refused.status)
        assertEquals("source_no_folder", refused.code)
        assertEquals(404, assertThrows(HttpException::class.java) { registry.requestSync("nope") }.status)
    }

    @Test
    fun `A change of one source does not touch the other`() {
        registry.add(FakeKind.source("sa"))
        registry.add(FakeKind.source("sb"))
        remote("sa").put("a.jpg", jpg(), "e1")
        remote("sb").put("b.jpg", jpg(), "e2")
        sync("sa")
        sync("sb")
        registry.patch(
            "sa",
            kotlinx.serialization.json.buildJsonObject { put("folder", kotlinx.serialization.json.JsonPrimitive("/Other")) },
        )
        assertEquals("/", configs.get("sb")!!.folder)
        assertEquals(setOf("b.jpg"), pathsOf("sb"))
    }
}
