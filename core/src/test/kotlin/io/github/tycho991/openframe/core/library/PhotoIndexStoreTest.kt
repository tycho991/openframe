package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.SkipReason
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PhotoIndexStoreTest {
    @TempDir
    lateinit var dir: File

    private val log = AppLog(TestClock())

    private fun store() = PhotoIndexStore(JsonStore(File(dir, "index.json"), StoredIndex.serializer(), TestScope(), log)).also { it.load() }

    private fun entry(
        path: String,
        taken: Long = 0,
        hidden: Boolean = false,
    ) = PhotoEntry(PhotoEntry.idFor("local", path), "local", path, 4, 3, taken, 10, 1, 1, hidden)

    @Test
    fun `Round trip and restart`() =
        runBlocking {
            val s = store()
            s.edit {
                it.put(entry("holiday/a.jpg", 2))
                it.put(entry("b.jpg", 1))
                it.setAlbumHidden("family", true)
                it.skip(SkippedFile("local", "x.heic", SkipReason.UNSUPPORTED, 5, 6))
            }
            s.flush()
            val reloaded = store().library.value
            assertEquals(s.library.value.photos, reloaded.photos)
            assertEquals(setOf("family"), reloaded.hiddenAlbums)
            assertEquals(listOf("x.heic"), reloaded.skipped.map { it.path })
            assertEquals(s.library.value.version, reloaded.version)
        }

    @Test
    fun `Version changes only on modification`() {
        val s = store()
        val v0 = s.library.value.version
        s.edit { it.put(entry("a.jpg")) }
        val v1 = s.library.value.version
        assertNotEquals(v0, v1)
        s.edit { it.put(entry("a.jpg")) }
        assertEquals(v1, s.library.value.version, "same content, no new version")
        s.edit { it.remove(entry("a.jpg").id) }
        assertNotEquals(v1, s.library.value.version)
    }

    @Test
    fun `Visibility from photo and album`() {
        val s = store()
        s.edit {
            it.put(entry("holiday/1.jpg", 1))
            it.put(entry("holiday/2.jpg", 2, hidden = true))
            it.put(entry("family/3.jpg", 3))
            it.put(entry("4.jpg", 4))
        }
        assertEquals(
            listOf("holiday/1.jpg", "family/3.jpg", "4.jpg"),
            s.library.value.visible
                .map { it.path },
        )
        s.edit { it.setAlbumHidden("holiday", true) }
        assertEquals(
            listOf("family/3.jpg", "4.jpg"),
            s.library.value.visible
                .map { it.path },
        )
        assertEquals("holiday", entry("holiday/2025/beach.jpg").album)
        assertEquals(null, entry("4.jpg").album)
    }

    @Test
    fun `Stable IDs`() {
        assertEquals(PhotoEntry.idFor("local", "holiday/1.jpg"), PhotoEntry.idFor("local", "holiday/1.jpg"))
        assertNotEquals(PhotoEntry.idFor("local", "holiday/1.jpg"), PhotoEntry.idFor("local", "holiday/2.jpg"))
        assertEquals(true, PhotoEntry.idFor("local", "a.jpg").matches(Regex("local-[0-9a-f]{40}")))
    }
}
