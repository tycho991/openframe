package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `photo-library`: albums as folder paths, inherited visibility and the reset of a branch. */
class AlbumTreeTest {
    @TempDir
    lateinit var dir: File

    private val store by lazy {
        PhotoIndexStore(JsonStore(File(dir, "index.json"), StoredIndex.serializer(), TestScope(), AppLog(TestClock()))).also { it.load() }
    }

    private fun entry(
        path: String,
        taken: Long = 0,
        source: String = "local",
        hidden: Boolean = false,
    ) = PhotoEntry(PhotoEntry.idFor(source, path), source, path, 4, 3, taken, 10, 1, 1, hidden)

    private fun quarantined(entry: PhotoEntry) = entry.copy(quarantinedVersion = entry.version)

    /** [count] photos in [album], the first [hidden] of them hidden themselves. */
    private fun putPhotos(
        album: String,
        count: Int,
        hidden: Int = 0,
    ) = store.edit { edit -> for (i in 0 until count) edit.put(entry("$album/$i.jpg", hidden = i < hidden)) }

    private fun counts(album: String) = library().albums.getValue(album).let { it.count to it.visibleCount }

    private fun library() = store.library.value

    private fun visiblePaths() = library().visible.map { it.path }.toSet()

    private fun hide(
        album: String,
        hidden: Boolean = true,
    ) = store.edit { it.setAlbumHidden(album, hidden) }

    private fun putAll(vararg paths: String) = store.edit { edit -> paths.forEach { edit.put(entry(it)) } }

    @Test
    fun `The album is the folder path`() {
        assertNull(entry("beach.jpg").album)
        assertEquals("holiday", entry("holiday/beach.jpg").album)
        assertEquals("holiday/2024/may", entry("holiday/2024/may/beach.jpg").album)
        assertEquals(listOf("holiday"), AlbumPaths.withAncestors("holiday"))
        assertEquals(listOf("holiday", "holiday/2024", "holiday/2024/may"), AlbumPaths.withAncestors("holiday/2024/may"))
    }

    @Test
    fun `The photo ID comes from the path, not from the album`() {
        assertEquals("local-3089dec1426a8a12465c27f9b4d7ae986deadb6d", entry("holiday/2024/x.jpg").id)
    }

    @Test
    fun `Sub-album of a hidden album`() {
        putAll("holiday/2024/may/a.jpg", "other/b.jpg")
        hide("holiday")
        assertEquals(setOf("other/b.jpg"), visiblePaths())
        assertTrue(library().albums.getValue("holiday/2024/may").hidden)
    }

    @Test
    fun `Sub-album shown inside a hidden album`() {
        putAll("holiday/h.jpg", "holiday/2023/a.jpg", "holiday/2024/b.jpg", "holiday/2024/may/c.jpg")
        hide("holiday")
        hide("holiday/2024", false)
        assertEquals(setOf("holiday/2024/b.jpg", "holiday/2024/may/c.jpg"), visiblePaths())
        assertTrue(library().albums.getValue("holiday/2024").overridesParent)
        assertFalse(library().albums.getValue("holiday/2024/may").overridesParent)
        assertFalse(library().albums.getValue("holiday").overridesParent, "a top-level album has no parent to override")
    }

    @Test
    fun `Exception inside an exception`() {
        putAll("holiday/2024/may/a.jpg", "holiday/2024/june/b.jpg")
        hide("holiday")
        hide("holiday/2024", false)
        hide("holiday/2024/june")
        assertEquals(setOf("holiday/2024/may/a.jpg"), visiblePaths())
    }

    @Test
    fun `A photo copied into a shown sub-album later is visible`() {
        putAll("holiday/2024/a.jpg")
        hide("holiday")
        hide("holiday/2024", false)
        putAll("holiday/2024/new/b.jpg")
        assertEquals(setOf("holiday/2024/a.jpg", "holiday/2024/new/b.jpg"), visiblePaths())
    }

    @Test
    fun `Hidden photo in a shown sub-album`() {
        putAll("holiday/2024/a.jpg")
        store.edit { it.put(entry("holiday/2024/b.jpg").copy(hidden = true)) }
        hide("holiday")
        hide("holiday/2024", false)
        assertEquals(setOf("holiday/2024/a.jpg"), visiblePaths())
    }

    @Test
    fun `Parent switched with an exception below`() {
        putAll("holiday/2024/a.jpg")
        hide("holiday")
        hide("holiday/2024", false)
        hide("holiday", false)
        hide("holiday")
        val album = library().albums.getValue("holiday/2024")
        assertTrue(album.hidden)
        assertFalse(album.overridesParent)
        assertEquals(mapOf("holiday" to true), library().albumStates)
    }

    @Test
    fun `Back to the parent's state`() {
        putAll("holiday/2024/a.jpg")
        hide("holiday")
        hide("holiday/2024", false)
        hide("holiday/2024")
        assertFalse("holiday/2024" in library().albumStates, "no setting of its own")
        hide("holiday", false)
        assertEquals(setOf("holiday/2024/a.jpg"), visiblePaths(), "follows holiday again")
        assertEquals(emptyMap<String, Boolean>(), library().albumStates, "showing a top-level album needs no setting")
    }

    @Test
    fun `An album with photos only in a sub-album`() {
        store.edit {
            it.put(entry("holiday/2024/may/old.jpg", taken = 1))
            it.put(entry("holiday/2024/june/new.jpg", taken = 2))
        }
        val albums = library().albums
        assertEquals(listOf("holiday", "holiday/2024", "holiday/2024/june", "holiday/2024/may"), albums.keys.toList())
        assertEquals(2, albums.getValue("holiday").count)
        assertEquals("holiday/2024/june/new.jpg", albums.getValue("holiday").newest?.path, "cover from the whole branch")
        assertEquals(1, albums.getValue("holiday/2024/may").count)
    }

    @Test
    fun `Same path in two sources is one album`() {
        store.edit {
            it.put(entry("holiday/2025/a.jpg", source = "local"))
            it.put(entry("holiday/2025/b.jpg", source = "s1"))
        }
        assertEquals(2, library().albums.getValue("holiday/2025").count)
        hide("holiday/2025")
        assertEquals(emptySet<String>(), visiblePaths())
    }

    @Test
    fun `Tree order puts sub-albums right after their parent`() {
        putAll("a b/1.jpg", "a/x/2.jpg", "B/3.jpg", "a/4.jpg")
        assertEquals(listOf("a", "a/x", "a b", "B"), library().albums.keys.toList())
    }

    @Test
    fun `Tree order with names that differ only in case`() {
        putAll("a/1.jpg", "A/x/2.jpg", "A/3.jpg")
        assertEquals(listOf("A", "A/x", "a"), library().albums.keys.toList())
    }

    /** Spec `photo-library`: Album list through the API › Counts of a branch. */
    @Test
    fun `Counts of a branch`() {
        putPhotos("holiday", 10, hidden = 2)
        putPhotos("holiday/2024", 30, hidden = 3)
        assertEquals(40 to 35, counts("holiday"))
        assertEquals(30 to 27, counts("holiday/2024"))
    }

    /** Spec `photo-library`: Album list through the API › Hidden by the album and by the photo. */
    @Test
    fun `Hidden by the album and by the photo`() {
        putPhotos("holiday", 10)
        putPhotos("holiday/2024", 30, hidden = 2)
        hide("holiday")
        hide("holiday/2024", false)
        assertEquals(40 to 28, counts("holiday"))
        assertEquals(30 to 28, counts("holiday/2024"))
    }

    /** Spec `photo-library`: Album list through the API › Quarantined photo. */
    @Test
    fun `Quarantined photo`() {
        store.edit {
            it.put(entry("holiday/old.jpg", taken = 1))
            it.put(quarantined(entry("holiday/new.jpg", taken = 2)))
            it.put(quarantined(entry("holiday/hidden.jpg", taken = 3, hidden = true)))
        }
        assertEquals(3 to 2, counts("holiday"), "counted by its hidden state alone")
        assertEquals(
            "holiday/old.jpg",
            library()
                .albums
                .getValue("holiday")
                .newest
                ?.path,
            "the newest photo that is not quarantined",
        )
    }

    /** Spec `photo-library`: Album list through the API › Album with only quarantined photos. */
    @Test
    fun `Album with only quarantined photos`() {
        store.edit {
            it.put(quarantined(entry("holiday/a.jpg", taken = 1)))
            it.put(quarantined(entry("holiday/2024/b.jpg", taken = 2)))
            it.put(entry("home/c.jpg"))
        }
        assertNull(library().albums.getValue("holiday").newest)
        assertNull(library().albums.getValue("holiday/2024").newest)
        assertEquals(
            "home/c.jpg",
            library()
                .albums
                .getValue("home")
                .newest
                ?.path,
        )
    }

    /** Spec `photo-library`: Photos without an album in the API › Counts without an album. */
    @Test
    fun `Counts without an album`() {
        assertEquals(AlbumCounts(0, 0), library().noAlbum, "empty library")
        store.edit {
            it.put(entry("a.jpg"))
            it.put(entry("b.jpg", hidden = true))
            it.put(entry("c.jpg", source = "s1"))
            it.put(entry("holiday/d.jpg"))
        }
        hide("holiday")
        assertEquals(AlbumCounts(3, 2), library().noAlbum)
    }

    /** The counts follow [Library.isVisible] in a mixed library with exceptions, hidden and quarantined photos. */
    @Test
    fun `visibleCount counts the photos for which isVisible is true`() {
        store.edit { edit ->
            for (i in 0 until 200) {
                val path = "level${i % 3}/sub${i % 4}/deeper${i % 5}/$i.jpg".let { if (i % 11 == 0) "$i.jpg" else it }
                val e = entry(path, taken = i.toLong(), source = if (i % 2 == 0) "local" else "s1", hidden = i % 7 == 0)
                edit.put(if (i % 13 == 0) quarantined(e) else e)
            }
        }
        hide("level1")
        hide("level1/sub2", false)
        hide("level1/sub2/deeper3")
        hide("level2/sub0")
        val lib = library()
        val photos = lib.photos.values
        // isVisible before anything else reads the albums builds the summary from inside isVisible.
        assertTrue(photos.count { lib.isVisible(it) } in 1 until photos.size)
        assertTrue(lib.albums.size > 30)
        for (album in lib.albums.values) {
            val branch = photos.filter { AlbumPaths.inBranch(it.album, album.name) }
            assertEquals(branch.size, album.count, album.name)
            assertEquals(branch.count { lib.isVisible(it) }, album.visibleCount, album.name)
        }
        val loose = photos.filter { it.album == null }
        assertEquals(AlbumCounts(loose.size, loose.count { lib.isVisible(it) }), lib.noAlbum)
    }
}
