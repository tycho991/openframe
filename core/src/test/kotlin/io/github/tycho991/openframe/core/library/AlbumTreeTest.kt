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
    ) = PhotoEntry(PhotoEntry.idFor(source, path), source, path, 4, 3, taken, 10, 1, 1)

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
        assertEquals("holiday/2024/june/new.jpg", albums.getValue("holiday").newest.path, "cover from the whole branch")
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

    /** Spec `photo-library`: Photo list through the API; the same 50 ms as `PhotoListPerformanceTest`. */
    @Test
    fun `Visible photos of 10000 photos in 4 levels in time`() {
        val photos =
            (0 until 10_000).associate { i ->
                val path = "level${i % 5}/sub${i % 7}/deeper${i % 11}/deepest${i % 13}/photo$i.jpg"
                val e = entry(path, taken = i.toLong())
                e.id to e
            }
        val states = mapOf("level1" to true, "level1/sub2" to false, "level1/sub2/deeper3/deepest4" to true, "level3/sub1" to true)
        repeat(5) { Library(photos, states, emptyList(), it.toLong()).visible }
        val times =
            (0 until 9).map {
                val fresh = Library(photos, states, emptyList(), it.toLong())
                val start = System.nanoTime()
                fresh.visible
                (System.nanoTime() - start) / 1_000_000
            }
        val median = times.sorted()[times.size / 2]
        assertTrue(median < 50, "Median $median ms, all: $times")
    }
}
