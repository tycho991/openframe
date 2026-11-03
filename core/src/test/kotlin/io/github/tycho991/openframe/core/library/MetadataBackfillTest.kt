package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.DecoderUnavailableException
import io.github.tycho991.openframe.core.platform.GeoPoint
import io.github.tycho991.openframe.core.platform.ImageException
import io.github.tycho991.openframe.core.platform.ImageMetadata
import io.github.tycho991.openframe.core.platform.MediaEngine
import io.github.tycho991.openframe.core.platform.SkipReason
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
    fun `A damaged file is not tried again`() {
        Fixtures.copy("broken.jpg", File(photos, "broken.jpg"))
        index.edit { it.put(old("broken.jpg")) }
        val b = backfill()
        b.request()
        assertEquals(PhotoEntry.METADATA_VERSION, entry("broken.jpg").metadataVersion)
        assertNull(entry("broken.jpg").gps)
        b.request()
        assertEquals(1, engine.metadataReads.get())
    }

    @Test
    fun `A missing file is left for later, not marked as read`() {
        // Gone for a moment (storage not mounted yet at boot, the file being replaced): it keeps its chance.
        index.edit { it.put(old("later.jpg")) }
        val b = backfill()
        b.request()
        assertEquals(0, entry("later.jpg").metadataVersion)
        assertEquals(0, engine.metadataReads.get(), "nothing opened")
        Fixtures.copy("gps-siena.jpg", File(photos, "later.jpg"))
        b.request()
        assertEquals(43.3188, entry("later.jpg").gps!!.lat, 1e-6)
    }

    @Test
    fun `Only a scan that changed something starts a run`() {
        var runs = 0
        val counting =
            MetadataBackfill(index, engine, ::fileOf, log, executor = {
                runs++
                it.run()
            })
        counting.scanned(ScanResult(0, 0, 0, 0))
        assertEquals(0, runs)
        counting.scanned(ScanResult(1, 0, 0, 0))
        counting.scanned(ScanResult(0, 0, 1, 0))
        assertEquals(2, runs)
    }

    @Test
    fun `A request after closing does nothing`() {
        val b = MetadataBackfill(index, engine, ::fileOf, log)
        b.close()
        // A sync that ends after the core stopped asks once more; that must not throw into the sync.
        b.request()
        b.request()
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

    /** Design decision 2 of `crash-resilience`: a crash of the decoder marks the metadata as read. */
    @Test
    fun `A file that crashes the decoder is marked as read without a position`() {
        Fixtures.copy("gps-siena.jpg", File(photos, "a.jpg"))
        index.edit { it.put(old("a.jpg")) }
        engine.metadataFailure = { ImageException(SkipReason.CRASHED, "The decoder died") }
        backfill().request()
        assertEquals(PhotoEntry.METADATA_VERSION, entry("a.jpg").metadataVersion)
        assertNull(entry("a.jpg").gps)
    }

    /** Spec `kiosk-host`: Decoding in a separate process › Decoder cannot be started. */
    @Test
    fun `A decoder that cannot be started leaves the metadata to the next run`() {
        val paths = (1..60).map { "%03d.jpg".format(it) }
        paths.forEach { Fixtures.copy("gps-siena.jpg", File(photos, it)) }
        index.edit { edit -> paths.forEach { edit.put(old(it)) } }
        engine.metadataFailure = { DecoderUnavailableException("not bound") }
        backfill().request()
        assertTrue(paths.all { entry(it).metadataVersion == 0 }, "nothing marked as read")
        assertEquals(1, engine.metadataReads.get(), "the run stops at the first file")

        engine.metadataFailure = { null }
        backfill().request()
        assertTrue(paths.all { entry(it).metadataVersion == PhotoEntry.METADATA_VERSION })
        assertEquals(43.3188, entry("001.jpg").gps!!.lat, 1e-6)
    }
}
