package io.github.tycho991.openframe.core.platform

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.testing.TestClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Design decisions 1 and 2 of `scan-efficiency`; spec `photo-library`, "Cost of an unchanged scan". */
class FolderListerTest {
    @TempDir
    lateinit var dir: File

    private val log = AppLog(TestClock())

    private fun file(
        path: String,
        bytes: Int = 10,
    ) = File(dir, path).apply {
        parentFile.mkdirs()
        writeBytes(ByteArray(bytes))
    }

    /** The listing before this change, as `LocalPhotoSource.listImageFiles` had it. */
    private fun walkTopDown(root: File): Map<String, File> =
        root
            .walkTopDown()
            .onEnter { it == root || !it.name.startsWith('.') }
            .filter { it.isFile && !it.name.startsWith('.') && ImageFormats.hasImageExtension(it.name) }
            .associateBy { it.relativeTo(root).invariantSeparatorsPath }

    @Test
    fun `The JVM lister gives the same paths as walkTopDown, with size and time`() {
        file("a.jpg")
        file("b.PNG", 20)
        file("note.txt")
        file(".hidden.jpg")
        file(".trash/x.jpg")
        file("holiday/2024/c.webp")
        file("holiday/.cache/d.jpg")
        file("x.jpg/inside.jpg")
        file("movie.mp4")
        val listing = JvmFolderLister.list(dir)!!
        assertEquals(walkTopDown(dir).keys, listing.files.keys)
        assertEquals(setOf("a.jpg", "b.PNG", "holiday/2024/c.webp", "x.jpg/inside.jpg"), listing.files.keys)
        val b = listing.files.getValue("b.PNG")
        assertEquals(20, b.size)
        assertEquals(File(dir, "b.PNG").lastModified(), b.modifiedMs)
        assertEquals(0, listing.unreadable)
    }

    @Test
    fun `A deep tree does not overflow the stack`() {
        var path = ""
        repeat(200) { path += "d$it/" }
        file("${path}deep.jpg")
        assertEquals(setOf("${path}deep.jpg"), JvmFolderLister.list(dir)!!.files.keys)
    }

    @Test
    fun `A folder that cannot be listed gives no listing`() {
        assertNull(JvmFolderLister.list(File(dir, "missing")))
    }

    private fun shifted(by: Long) =
        FolderLister { root ->
            JvmFolderLister.list(root)?.let { listing ->
                listing.copy(files = listing.files.mapValues { (_, f) -> f.copy(modifiedMs = f.modifiedMs + by) })
            }
        }

    private fun fallbacks() = log.recent(100).count { it.message.startsWith("The fast folder listing gives other file times") }

    @Test
    fun `A fast lister with other times falls back to java io once`() {
        file("a.jpg")
        file("b.jpg")
        val checked = CheckedFolderLister(shifted(500), JvmFolderLister, log)
        val first = checked.list(dir)!!
        assertEquals(File(dir, "a.jpg").lastModified(), first.files.getValue("a.jpg").modifiedMs, "the first listing is java.io's")
        assertEquals(1, fallbacks())
        val second = checked.list(dir)!!
        assertEquals(File(dir, "a.jpg").lastModified(), second.files.getValue("a.jpg").modifiedMs)
        assertEquals(1, fallbacks(), "logged once")
    }

    @Test
    fun `A fast lister with the same times is used without a log line`() {
        file("a.jpg")
        var fastCalls = 0
        val fast =
            FolderLister { root ->
                fastCalls++
                JvmFolderLister.list(root)
            }
        val checked = CheckedFolderLister(fast, shifted(500), log)
        checked.list(dir)
        checked.list(dir)
        assertEquals(2, fastCalls)
        assertEquals(0, fallbacks())
    }

    @Test
    fun `An empty folder postpones the check`() {
        File(dir, "empty").mkdirs()
        val checked = CheckedFolderLister(shifted(500), JvmFolderLister, log)
        assertTrue(checked.list(File(dir, "empty"))!!.files.isEmpty())
        assertEquals(0, fallbacks(), "nothing to compare yet")
        file("empty/a.jpg")
        val listing = checked.list(File(dir, "empty"))!!
        assertEquals(1, fallbacks(), "checked at the first listing with a file")
        assertEquals(File(dir, "empty/a.jpg").lastModified(), listing.files.getValue("a.jpg").modifiedMs)
    }

    @Test
    fun `A file deleted after the listing does not count as a mismatch`() {
        file("a.jpg")
        file("b.jpg")
        val fast =
            FolderLister { root ->
                JvmFolderLister.list(root).also { File(root, "a.jpg").delete() }
            }
        val checked = CheckedFolderLister(fast, shifted(500), log)
        val listing = checked.list(dir)!!
        assertEquals(0, fallbacks())
        assertTrue("b.jpg" in listing.files, "the fast listing is kept")
    }
}
