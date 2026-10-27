package io.github.tycho991.openframe.core.slideshow

import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.settings.CaptionSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Spec `slideshow-display`, requirements "Caption over the photo" and "Album title in the caption". */
class CaptionTextTest {
    private fun photo(
        path: String,
        comment: String? = null,
    ) = PhotoEntry(PhotoEntry.idFor("local", path), "local", path, 400, 300, 0, 10, 1, 1, comment = comment)

    private val off = CaptionSettings(albumTitle = false, place = false)
    private val on = CaptionSettings(albumTitle = true, place = true)

    @Test
    fun `Comment only`() {
        assertEquals(
            CaptionText("Grandma's 80th birthday", null),
            CaptionText.of(photo("holiday/2024/Tuscany/a.jpg", "Grandma's 80th birthday"), off, "Siena, Italy"),
        )
    }

    @Test
    fun `Comment, album title and place`() {
        assertEquals(
            CaptionText("Tuscany · Grandma's 80th birthday", "Siena, Italy"),
            CaptionText.of(photo("holiday/2024/Tuscany/a.jpg", "Grandma's 80th birthday"), on, "Siena, Italy"),
        )
    }

    @Test
    fun `Nothing to show`() {
        assertNull(CaptionText.of(photo("holiday/a.jpg"), off, "Siena, Italy"))
    }

    @Test
    fun `Album title without comment`() {
        assertEquals(CaptionText("Tuscany", null), CaptionText.of(photo("holiday/2024/Tuscany/a.jpg"), on.copy(place = false), null))
        assertEquals(CaptionText("holiday", null), CaptionText.of(photo("holiday/a.jpg"), on, null), "a top-level album")
    }

    @Test
    fun `Photo without album`() {
        // Directly in the folder of its source, without comment and without position.
        assertNull(CaptionText.of(photo("a.jpg"), on, null))
        assertEquals(CaptionText("Comment", null), CaptionText.of(photo("a.jpg", "Comment"), on, null), "no title in front")
    }

    @Test
    fun `The place alone`() {
        assertEquals(CaptionText(null, "Siena, Italy"), CaptionText.of(photo("a.jpg"), on, "Siena, Italy"))
        assertNull(CaptionText.of(photo("a.jpg"), on.copy(albumTitle = false), null), "place on, but no place")
    }
}
