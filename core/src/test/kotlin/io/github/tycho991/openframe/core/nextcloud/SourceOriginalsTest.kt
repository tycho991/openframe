package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.library.LocalPhotoSource
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.PhotoIndexStore
import io.github.tycho991.openframe.core.library.PixelSize
import io.github.tycho991.openframe.core.library.RenderCache
import io.github.tycho991.openframe.core.library.RenderSize
import io.github.tycho991.openframe.core.library.SourceOriginals
import io.github.tycho991.openframe.core.library.StoredIndex
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `nextcloud-source`: Storage of the originals (a rendering after eviction); `photo-library`: Rendered image sizes. */
class SourceOriginalsTest {
    @TempDir
    lateinit var dir: File

    private val log = AppLog(TestClock())
    private val engine = JvmMediaEngine()
    private val clients = FakeClients()
    private val index by lazy {
        PhotoIndexStore(JsonStore(File(dir, "index.json"), StoredIndex.serializer(), TestScope(), log)).also { it.load() }
    }
    private val configs by lazy {
        NextcloudConfigStore(JsonStore(File(dir, "nextcloud.json"), StoredNextcloud.serializer(), TestScope(), log)).also {
            it.load()
            it.connect("https://cloud.example.org", "anna", "Anna", "app-pw")
            it.patch(kotlinx.serialization.json.buildJsonObject { put("folder", kotlinx.serialization.json.JsonPrimitive("/")) })
        }
    }
    private val originals by lazy { OriginalsStore(File(dir, "originals")) }
    private val nextcloud by lazy { NextcloudSource(configs, clients, index, engine, originals, {}, log) }
    private val local by lazy { LocalPhotoSource(File(dir, "photos"), engine, index, log) }
    private val cache by lazy {
        RenderCache(
            File(dir, "renders"),
            engine,
            SourceOriginals(local, nextcloud)::fileOf,
            { PixelSize(320, 200) },
            { 10L * 1024 * 1024 },
            log,
        )
    }

    @AfterEach
    fun close() = cache.close()

    @Test
    fun `A local photo is rendered from the photo folder`() {
        Fixtures.copy("landscape.jpg", File(dir, "photos/a.jpg"))
        val entry = PhotoEntry(PhotoEntry.idFor("local", "a.jpg"), "local", "a.jpg", 400, 300, 0, 1, 1, 1)
        val rendered = runBlocking { cache.get(entry, RenderSize.THUMB) }
        assertTrue(rendered.isFile)
        assertEquals(emptyList<String>(), clients.remote.downloads)
    }

    @Test
    fun `A Nextcloud photo whose original was removed is fetched and rendered`() {
        clients.remote.put("holiday/b.jpg", Fixtures.file("landscape.jpg").readBytes(), "e1")
        runBlocking { nextcloud.scan() }
        val entry =
            index.library.value.photos.values
                .single { it.source == "nextcloud" }
        originals.fileOf(entry).delete()
        clients.remote.downloads.clear()
        val rendered = runBlocking { cache.get(entry, RenderSize.THUMB) }
        assertTrue(rendered.isFile)
        assertEquals(listOf("holiday/b.jpg"), clients.remote.downloads)
        assertTrue(originals.has(entry))
    }

    @Test
    fun `A rendering fails when the original cannot be fetched`() {
        clients.remote.put("b.jpg", Fixtures.file("landscape.jpg").readBytes(), "e1")
        runBlocking { nextcloud.scan() }
        val entry =
            index.library.value.photos.values
                .single { it.source == "nextcloud" }
        originals.fileOf(entry).delete()
        clients.remote.beforeDownload =
            { throw NextcloudException(NextcloudException.Kind.UNREACHABLE, "The server cloud is not reachable") }
        assertThrows(NextcloudException::class.java) { runBlocking { cache.get(entry, RenderSize.THUMB) } }
    }
}
