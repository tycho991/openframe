package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.SkipReason
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.store.StoreJson
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
            assertEquals(mapOf("family" to true), reloaded.albumStates)
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
    }

    @Test
    fun `Stable IDs`() {
        assertEquals(PhotoEntry.idFor("local", "holiday/1.jpg"), PhotoEntry.idFor("local", "holiday/1.jpg"))
        assertNotEquals(PhotoEntry.idFor("local", "holiday/1.jpg"), PhotoEntry.idFor("local", "holiday/2.jpg"))
        assertEquals(true, PhotoEntry.idFor("local", "a.jpg").matches(Regex("local-[0-9a-f]{40}")))
    }

    /** An index as the build before nested albums stored it: no `albumStates`, `holiday` hidden. */
    private fun writePreviousFormat() {
        val photo = entry("holiday/2024/beach.jpg")
        File(dir, "index.json").writeText(
            """
            {"photos":[{"id":"${photo.id}","source":"local","path":"holiday/2024/beach.jpg","width":4,"height":3,
            "takenAtMs":0,"fileSize":10,"modifiedMs":1,"version":1,"hidden":false}],
            "hiddenAlbums":["holiday"],"skipped":[],"libraryVersion":7}
            """.trimIndent(),
        )
    }

    /** Spec `photo-library`: Album states kept across the update › Hidden album with a deeper folder. */
    @Test
    fun `Hidden album of the previous format stays hidden for a deeper folder`() {
        writePreviousFormat()
        val library = store().library.value
        val photo = library.photos.values.single()
        assertEquals("holiday/2024", photo.album)
        assertFalse(library.isVisible(photo), "stays invisible to the slideshow")
        assertEquals(emptyList<PhotoEntry>(), library.visible)
        assertFalse("holiday/2024" in library.albumStates, "no setting of its own")
        assertFalse(library.albums.getValue("holiday/2024").overridesParent)
        assertEquals(mapOf("holiday" to true), library.albumStates)
    }

    @Test
    fun `The saved index still lists the hidden albums for an older build`() =
        runBlocking {
            writePreviousFormat()
            val s = store()
            s.edit { it.put(entry("other/x.jpg")) }
            s.edit { it.setAlbumHidden("other", true) }
            s.flush()
            val saved = Json.parseToJsonElement(File(dir, "index.json").readText()).jsonObject
            assertEquals(
                setOf("holiday", "other"),
                saved["hiddenAlbums"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
            )
            assertEquals(setOf("holiday", "other"), saved["albumStates"]!!.jsonObject.keys)
            // An older build reads only hiddenAlbums and ignores the rest.
            val older = StoreJson.decodeFromString(PreviousStoredIndex.serializer(), File(dir, "index.json").readText())
            assertEquals(setOf("holiday", "other"), older.hiddenAlbums)
        }

    @Test
    fun `An index saved again by an older build keeps no redundant exceptions`() {
        // Written by this build with `a/b` hidden, then `a` hidden by an older build, which drops albumStates.
        val photo = entry("a/b/x.jpg")
        File(dir, "index.json").writeText(
            """
            {"photos":[{"id":"${photo.id}","source":"local","path":"a/b/x.jpg","width":4,"height":3,
            "takenAtMs":0,"fileSize":10,"modifiedMs":1,"version":1,"hidden":false}],
            "hiddenAlbums":["a/b","a"],"skipped":[],"libraryVersion":3}
            """.trimIndent(),
        )
        val library = store().library.value
        assertEquals(mapOf("a" to true), library.albumStates)
        assertFalse(library.albums.getValue("a/b").overridesParent)
        assertFalse(library.isVisible(library.photos.values.single()))
    }

    /** The stored index of the build before nested albums, as far as album states go. */
    @Serializable
    private data class PreviousStoredIndex(
        val hiddenAlbums: Set<String> = emptySet(),
    )
}
