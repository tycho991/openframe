package io.github.tycho991.openframe.core.sources

import io.github.tycho991.openframe.core.library.LocalPhotoSource
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.PhotoIndexStore
import io.github.tycho991.openframe.core.library.PixelSize
import io.github.tycho991.openframe.core.library.RenderCache
import io.github.tycho991.openframe.core.library.RenderSize
import io.github.tycho991.openframe.core.library.StoredIndex
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `remote-sources`: Storage of the originals (a rendering after eviction), Several sources; `photo-library`: Rendered image sizes. */
class SourceOriginalsTest {
    @TempDir
    lateinit var dir: File

    private val scope = TestScope()
    private val log = AppLog(TestClock())
    private val engine = JvmMediaEngine()
    private val folders = HashMap<String, FakeRemoteFolder>()
    private val index by lazy {
        PhotoIndexStore(JsonStore(File(dir, "index.json"), StoredIndex.serializer(), TestScope(), log)).also { it.load() }
    }
    private val registry by lazy {
        val configs =
            SourceConfigStore(
                JsonStore(File(dir, "sources.json"), StoredSources.serializer(), TestScope(), log),
            ).also { it.load() }
        val results = SyncResultStore(JsonStore(File(dir, "status.json"), StoredSyncResults.serializer(), TestScope(), log))
        SourceRegistry(
            scope,
            configs,
            results,
            listOf(FakeKind { folders.getOrPut(it.id) { FakeRemoteFolder() } }),
            index,
            engine,
            File(dir, "sources"),
            {},
            TestClock(),
            log,
        ).also {
            it.start()
            it.add(FakeKind.source("nextcloud"))
            it.add(FakeKind.source("s0000beef"))
        }
    }
    private val local by lazy { LocalPhotoSource(File(dir, "photos"), engine, index, log) }
    private val cache by lazy {
        RenderCache(
            File(dir, "renders"),
            engine,
            SourceOriginals(local, registry)::fileOf,
            { PixelSize(320, 200) },
            { 10L * 1024 * 1024 },
            log,
        )
    }

    @AfterEach
    fun close() {
        cache.close()
        scope.cancel()
    }

    private fun remote(id: String) = folders.getOrPut(id) { FakeRemoteFolder() }

    private fun syncedEntry(
        source: String,
        path: String,
    ): PhotoEntry {
        registry
        remote(source).put(path, Fixtures.file("landscape.jpg").readBytes(), "e-$path")
        runBlocking { registry.source(source)!!.scan() }
        return index.library.value.photos.values
            .single { it.source == source && it.path == path }
    }

    @Test
    fun `A local photo and the photos of two sources are rendered`() {
        Fixtures.copy("landscape.jpg", File(dir, "photos/a.jpg"))
        val localEntry = PhotoEntry(PhotoEntry.idFor("local", "a.jpg"), "local", "a.jpg", 400, 300, 0, 1, 1, 1)
        val first = syncedEntry("nextcloud", "holiday/b.jpg")
        val second = syncedEntry("s0000beef", "holiday/c.jpg")
        for (entry in listOf(localEntry, first, second)) {
            assertTrue(runBlocking { cache.get(entry, RenderSize.THUMB) }.isFile, entry.id)
        }
        assertEquals(listOf("holiday/b.jpg"), remote("nextcloud").downloads, "rendering took the cached originals")
        assertEquals(listOf("holiday/c.jpg"), remote("s0000beef").downloads)
    }

    @Test
    fun `A photo whose original was removed is fetched from its own source and rendered`() {
        val entry = syncedEntry("s0000beef", "holiday/b.jpg")
        val originals = registry.source("s0000beef")!!.originals
        originals.fileOf(entry).delete()
        remote("s0000beef").downloads.clear()
        val rendered = runBlocking { cache.get(entry, RenderSize.THUMB) }
        assertTrue(rendered.isFile)
        assertEquals(listOf("holiday/b.jpg"), remote("s0000beef").downloads)
        assertEquals(emptyList<String>(), remote("nextcloud").downloads)
        assertTrue(originals.has(entry))
    }

    @Test
    fun `A rendering fails when the original cannot be fetched or its source is gone`() {
        val entry = syncedEntry("nextcloud", "b.jpg")
        registry
            .source("nextcloud")!!
            .originals
            .fileOf(entry)
            .delete()
        remote("nextcloud").beforeDownload =
            { throw SourceException(SourceException.Kind.UNREACHABLE, "The server cloud is not reachable") }
        assertThrows(SourceException::class.java) { runBlocking { cache.get(entry, RenderSize.THUMB) } }
        val orphan = entry.copy(id = PhotoEntry.idFor("sgone", "b.jpg"), source = "sgone")
        assertThrows(SourceException::class.java) { runBlocking { cache.get(orphan, RenderSize.THUMB) } }
    }
}
