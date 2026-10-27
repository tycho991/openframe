package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.GeoPoint
import io.github.tycho991.openframe.core.platform.ImageMetadata
import io.github.tycho991.openframe.core.platform.MediaEngine
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Spec `photo-library`, requirement "Metadata of photos already in the index". */
class MetadataBackfillTest {
    @TempDir
    lateinit var dir: File

    private val log = AppLog(TestClock())
    private val engine = JvmMediaEngine()
    private val photos get() = File(dir, "photos")

    private fun store() = PhotoIndexStore(JsonStore(File(dir, "index.json"), StoredIndex.serializer(), TestScope(), log)).also { it.load() }

    private val index by lazy { store() }

    /** The file of a photo: local ones in the photo folder, remote ones in [originals] if they are there. */
    private val originals = HashMap<String, File>()

    private fun fileOf(entry: PhotoEntry): File? = if (entry.source == "local") File(photos, entry.path) else originals[entry.id]

    private fun backfill(
        engine: MediaEngine = this.engine,
        index: PhotoIndexStore = this.index,
    ) = MetadataBackfill(index, engine, ::fileOf, log, executor = { it.run() })

    /** An entry as a build before the GPS position left it in the index. */
    private fun old(
        path: String,
        source: String = "local",
    ) = PhotoEntry(PhotoEntry.idFor(source, path), source, path, 400, 300, 0, 10, 1, 1)

    private fun entry(path: String) =
        index.library.value.photos
            .getValue(PhotoEntry.idFor("local", path))

    @Test
    fun `Update with local photos`() {
        val paths = (1..120).map { "holiday/%03d.jpg".format(it) }
        paths.forEach { Fixtures.copy("gps-siena.jpg", File(photos, it)) }
        index.edit { edit -> paths.forEach { edit.put(old(it)) } }
        backfill().request()
        for (path in paths) {
            assertEquals(43.3188, entry(path).gps!!.lat, 1e-6, path)
            assertEquals(PhotoEntry.METADATA_VERSION, entry(path).metadataVersion)
            assertEquals(1, entry(path).version, "Not a new image version")
        }
        assertEquals(120, engine.metadataReads.get())
        assertEquals(0, engine.decodes.get(), "Nothing decoded")
        assertEquals(0, engine.renders.get(), "Nothing rendered again")
    }

    @Test
    fun `The library version changes once per batch`() {
        val paths = (1..120).map { "%03d.jpg".format(it) }
        paths.forEach { Fixtures.copy("landscape.jpg", File(photos, it)) }
        index.edit { edit -> paths.forEach { edit.put(old(it)) } }
        val before = index.library.value.version
        backfill().request()
        assertEquals(before + 3, index.library.value.version, "120 photos in batches of 50")
    }

    @Test
    fun `Remote photo without its original`() {
        val withOriginal = old("a.jpg", "nextcloud")
        val without = old("b.jpg", "nextcloud")
        originals[withOriginal.id] = Fixtures.file("gps-siena.jpg")
        index.edit {
            it.put(withOriginal)
            it.put(without)
        }
        backfill().request()
        val photos = index.library.value.photos
        assertEquals(43.3188, photos.getValue(withOriginal.id).gps!!.lat, 1e-6)
        assertNull(photos.getValue(without.id).gps)
        assertEquals(0, photos.getValue(without.id).metadataVersion, "Left for its next download")
        assertEquals(1, engine.metadataReads.get())
    }

    @Test
    fun `Photo without GPS data after the update`() =
        runBlocking {
            Fixtures.copy("landscape.jpg", File(photos, "a.jpg"))
            index.edit { it.put(old("a.jpg")) }
            backfill().request()
            assertNull(entry("a.jpg").gps)
            assertEquals(PhotoEntry.METADATA_VERSION, entry("a.jpg").metadataVersion)
            index.flush()
            // After a restart the photo is not read again.
            val restarted = store()
            backfill(index = restarted).request()
            backfill(index = restarted).request()
            assertEquals(1, engine.metadataReads.get())
        }

    @Test
    fun `A file that cannot be read is not tried again`() {
        Fixtures.copy("broken.jpg", File(photos, "broken.jpg"))
        index.edit {
            it.put(old("broken.jpg"))
            it.put(old("gone.jpg"))
        }
        val b = backfill()
        b.request()
        assertEquals(PhotoEntry.METADATA_VERSION, entry("broken.jpg").metadataVersion)
        assertEquals(PhotoEntry.METADATA_VERSION, entry("gone.jpg").metadataVersion)
        assertNull(entry("gone.jpg").gps)
        b.request()
        assertEquals(2, engine.metadataReads.get())
    }

    @Test
    fun `A file replaced during a batch keeps its own new result`() {
        Fixtures.copy("gps-siena.jpg", File(photos, "a.jpg"))
        Fixtures.copy("gps-siena.jpg", File(photos, "b.jpg"))
        index.edit {
            it.put(old("a.jpg"))
            it.put(old("b.jpg"))
        }
        val rio = GeoPoint(-22.9068, -43.1729)
        // While the batch is read, the scan applies a new version of a.jpg with its own position.
        val racing =
            object : MediaEngine by engine {
                override fun readMetadata(file: File): ImageMetadata {
                    if (file.name == "a.jpg") {
                        index.edit {
                            it.put(
                                it.get(PhotoEntry.idFor("local", "a.jpg"))!!.copy(version = 2, gps = rio, metadataVersion = 1),
                            )
                        }
                    }
                    return engine.readMetadata(file)
                }
            }
        backfill(racing).request()
        assertEquals(rio, entry("a.jpg").gps, "The new version keeps the result of its own read")
        assertEquals(2, entry("a.jpg").version)
        assertEquals(43.3188, entry("b.jpg").gps!!.lat, 1e-6)
    }

    @Test
    fun `The comment and the hidden state stay`() {
        Fixtures.copy("gps-siena.jpg", File(photos, "a.jpg"))
        index.edit { it.put(old("a.jpg").copy(hidden = true, comment = "Grandma's 80th birthday")) }
        backfill().request()
        assertTrue(entry("a.jpg").hidden)
        assertEquals("Grandma's 80th birthday", entry("a.jpg").comment)
    }

    @Test
    fun `By default it runs on a thread of its own at minimum priority`() {
        Fixtures.copy("gps-siena.jpg", File(photos, "a.jpg"))
        index.edit { it.put(old("a.jpg")) }
        val ran = CountDownLatch(1)
        var priority = -1
        val watching =
            object : MediaEngine by engine {
                override fun readMetadata(file: File): ImageMetadata {
                    priority = Thread.currentThread().priority
                    return engine.readMetadata(file).also { ran.countDown() }
                }
            }
        val b = MetadataBackfill(index, watching, ::fileOf, log)
        try {
            b.request()
            assertTrue(ran.await(10, TimeUnit.SECONDS))
        } finally {
            b.close()
        }
        assertEquals(Thread.MIN_PRIORITY, priority)
    }
}
