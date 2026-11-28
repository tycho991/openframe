package io.github.tycho991.openframe.android

import io.github.tycho991.openframe.core.platform.FolderEntry
import io.github.tycho991.openframe.core.platform.JvmFolderLister
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Design decisions 1 and 2 of `scan-efficiency`: the Android lister's mapping of `stat`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidFolderListerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val file = File("/photos/a.jpg")

    @Test
    fun `A folder, an image file, another file and other types`() {
        assertSame(FolderEntry.Folder, AndroidFolderLister.entryOf(file, 0x4000 or 0x1ED, 4096, 0, image = true))
        val image = AndroidFolderLister.entryOf(file, 0x8000 or 0x1A4, 1234, 5_000, image = true)
        assertTrue(image is FolderEntry.ImageFile)
        val facts = (image as FolderEntry.ImageFile).facts
        assertEquals(1234L, facts.size)
        assertEquals(5_000L, facts.modifiedMs)
        assertSame(FolderEntry.Other, AndroidFolderLister.entryOf(file, 0x8000, 10, 0, image = false))
        // A symbolic link that stat cannot follow, a socket, a device: neither file nor folder.
        assertSame(FolderEntry.Other, AndroidFolderLister.entryOf(file, 0xA000, 10, 0, image = true))
        assertSame(FolderEntry.Other, AndroidFolderLister.entryOf(file, 0xC000, 0, 0, image = true))
    }

    @Test
    fun `An entry that is gone is left out, other errors count as unreadable`() {
        assertSame(FolderEntry.Other, AndroidFolderLister.entryOfError(errno = 2, enoent = 2))
        assertSame(FolderEntry.Unreadable, AndroidFolderLister.entryOfError(errno = 13, enoent = 2))
    }

    @Test
    fun `Times in whole seconds below API 27 and from the timespec from 27 on`() {
        assertEquals(1_700_000_000_000L, AndroidFolderLister.modifiedMs(21, 1_700_000_000, 0, 0))
        assertEquals(1_700_000_000_000L, AndroidFolderLister.modifiedMs(26, 1_700_000_000, 1_700_000_000, 987_654_321))
        assertEquals(1_700_000_000_987L, AndroidFolderLister.modifiedMs(27, 1_700_000_000, 1_700_000_000, 987_654_321))
    }

    @Test
    fun `The platform hands out the Android lister`() {
        assertTrue(AndroidPlatformInfo(RuntimeEnvironment.getApplication()).folderLister is AndroidFolderLister)
    }

    @Test
    fun `The Android lister finds the same image files as java io`() {
        val root = tmp.newFolder("photos")
        File(root, "holiday/2024").mkdirs()
        File(root, ".trash").mkdirs()
        listOf("a.jpg", "b.PNG", "note.txt", ".hidden.jpg", ".trash/x.jpg", "holiday/2024/c.webp").forEach {
            File(root, it).writeBytes(ByteArray(10))
        }
        val android = AndroidFolderLister().list(root)!!
        assertEquals(JvmFolderLister.list(root)!!.files.keys, android.files.keys)
        assertEquals(10L, android.files.getValue("a.jpg").size)
    }
}
