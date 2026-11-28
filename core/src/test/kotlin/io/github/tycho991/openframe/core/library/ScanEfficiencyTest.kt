package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.FileFacts
import io.github.tycho991.openframe.core.platform.FolderListing
import io.github.tycho991.openframe.core.platform.ImageInfo
import io.github.tycho991.openframe.core.platform.JvmFolderLister
import io.github.tycho991.openframe.core.platform.MediaEngine
import io.github.tycho991.openframe.core.platform.SkipReason
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.FakePlatform
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import io.github.tycho991.openframe.core.testing.TestClock
import io.github.tycho991.openframe.core.testing.TestFrame
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.Duration

/**
 * Spec `photo-library`: Cost of an unchanged scan, Files that vanish during a scan, Many new photos at once (change
 * `scan-efficiency`).
 */
class ScanEfficiencyTest {
    @TempDir
    lateinit var dir: File

    private val clock = TestClock()
    private val log = AppLog(clock)
    private val engine = JvmMediaEngine(supportsHeic = false)
    private val photos get() = File(dir, "photos")
    private val index by lazy {
        PhotoIndexStore(JsonStore(File(dir, "index.json"), StoredIndex.serializer(), TestScope(), log)).also { it.load() }
    }

    private fun source(
        engine: MediaEngine = this.engine,
        lister: (File) -> FolderListing? = { JvmFolderLister.list(it) },
    ) = LocalPhotoSource(photos, engine, index, log, clock = clock, lister = lister)

    private fun LocalPhotoSource.scanNow() = runBlocking { scan() }

    private fun copies(
        count: Int,
        folder: String = "",
    ): List<File> {
        val first = Fixtures.copy("landscape.jpg", File(photos, "${folder}p0.jpg"))
        return listOf(first) + (1 until count).map { first.copyTo(File(photos, "${folder}p$it.jpg")) }
    }

    private fun lines(prefix: String) = log.recent(1000).filter { it.message.startsWith(prefix) }

    @Test
    fun `The scan compares the facts of the listing and stats nothing again`() {
        copies(3)
        source().scanNow()
        val probes = engine.probes.get()
        // The listing reports another time for one file, as if it had changed; the file itself did not.
        val other =
            source(lister = { root ->
                JvmFolderLister.list(root)!!.let { listing ->
                    val a = listing.files.getValue("p1.jpg")
                    listing.copy(files = listing.files + ("p1.jpg" to a.copy(modifiedMs = a.modifiedMs + 1_000)))
                }
            })
        other.scanNow()
        assertEquals(probes + 1, engine.probes.get(), "only the file whose listed time differs is read")
    }

    @Test
    fun `Nothing changed with 5,000 photos opens no file and keeps the version`() {
        copies(5_000, "big/")
        val source = source()
        source.scanNow()
        assertEquals(5_000, index.library.value.photos.size)
        val probes = engine.probes.get()
        val version = index.library.value.version
        val edits = index.edits
        source.scanNow()
        assertEquals(probes, engine.probes.get(), "no file opened")
        assertEquals(version, index.library.value.version)
        assertEquals(edits, index.edits, "no edit")
    }

    @Test
    fun `Known files gone by the time they are read are removed, not skipped`() {
        val files = copies(50)
        source().scanNow()
        assertEquals(50, index.library.value.photos.size)
        // As on the frame: a folder deleted while the scan stats it, so its files are listed with size and time 0.
        val vanishing =
            source(lister = { root ->
                val listing = JvmFolderLister.list(root)!!
                files.forEach { it.delete() }
                listing.copy(files = listing.files.mapValues { (_, f) -> FileFacts(f.file, 0, 0) })
            })
        val probes = engine.probes.get()
        vanishing.scanNow()
        assertTrue(engine.probes.get() <= probes + 50)
        assertTrue(
            index.library.value.skipped
                .isEmpty(),
            "nothing on the skipped list",
        )
        assertTrue(lines("Error reading").isEmpty(), "no read error logged")
        assertTrue(lines("Scan:").last().message.contains("removed=50"), lines("Scan:").last().message)
        source().scanNow()
        assertTrue(
            index.library.value.photos
                .isEmpty(),
            "the entries are gone",
        )
    }

    @Test
    fun `New files gone by the time they are read leave nothing`() {
        photos.mkdirs()
        val ghosts = (0 until 50).associate { "g$it.jpg" to FileFacts(File(photos, "g$it.jpg"), 1_000, 1_700_000_000_000) }
        source(lister = { FolderListing(ghosts) }).scanNow()
        assertTrue(
            index.library.value.photos
                .isEmpty(),
        )
        assertTrue(
            index.library.value.skipped
                .isEmpty(),
            "nothing on the skipped list",
        )
        assertTrue(lines("Error reading").isEmpty(), "no read error logged")
    }

    @Test
    fun `Many unreadable files give one stack trace and one count`() {
        copies(100)
        engine.probeFailure = { IOException("The decoder failed: broken pipe") }
        source().scanNow()
        val skipped = index.library.value.skipped
        assertEquals(100, skipped.size)
        assertTrue(skipped.all { it.reason == SkipReason.CORRUPT })
        val errors = lines("Error reading")
        assertEquals(1, errors.size, "the first error only")
        assertTrue(errors.single().message.contains("\n"), "with its stack trace")
        assertEquals(1, lines("99 more files could not be read").size)
    }

    @Test
    fun `A platform lister with other times is checked and replaced by java io`() {
        // A frame whose own lister reports times 500 ms off, as a lister could on a release whose File.lastModified()
        // differs: the frame must not read its known photos again (spec "Cost of an unchanged scan" › Times read
        // another way).
        val shifted =
            io.github.tycho991.openframe.core.platform.FolderLister { root ->
                JvmFolderLister.list(root)?.let { listing ->
                    listing.copy(files = listing.files.mapValues { (_, f) -> f.copy(modifiedMs = f.modifiedMs + 500) })
                }
            }
        val first = TestFrame(File(dir, "frame"))
        first.use {
            Fixtures.copy("landscape.jpg", File(it.photos, "a.jpg"))
            Fixtures.copy("image.png", File(it.photos, "b.png"))
            it.scan()
        }
        val platform = FakePlatform().apply { lister = shifted }
        TestFrame(File(dir, "frame"), platform = platform).use { frame ->
            val probes = frame.engine.probes.get()
            assertEquals(ScanResult(0, 0, 0, 0), frame.scan())
            assertEquals(ScanResult(0, 0, 0, 0), frame.scan())
            assertEquals(probes, frame.engine.probes.get(), "no known photo read again")
            assertEquals(
                1,
                frame.core.log
                    .recent(200)
                    .count { it.message.startsWith("The fast folder listing gives other file times") },
            )
        }
    }

    @Test
    fun `Many new photos are applied in steps`() {
        copies(30)
        val countsDuringScan = mutableListOf<Int>()
        val slow =
            object : MediaEngine by engine {
                override fun probe(file: File): ImageInfo {
                    countsDuringScan += index.library.value.photos.size
                    clock.advance(Duration.ofSeconds(1))
                    return engine.probe(file)
                }
            }
        val source = source(engine = slow)
        val before = index.library.value.version
        source.scanNow()
        assertEquals(30, index.library.value.photos.size)
        // The probe of the 12th file comes after 11 seconds: the photos read in the first 10 seconds are in by then.
        assertTrue(countsDuringScan[11] >= 10, "after 11 s: ${countsDuringScan[11]} photos")
        assertTrue(countsDuringScan.distinct().size >= 3, "the count grows during the scan: $countsDuringScan")
        assertTrue(index.library.value.version > before)
        assertEquals(1, lines("Scan:").size, "one summary line")
        assertTrue(lines("Scan:").single().message.contains("added=30"))
    }
}
