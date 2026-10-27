package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.ValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Spec `photo-library`, requirements "Comment of a photo" and "Comment too long". */
class CommentsTest {
    @Test
    fun `Set comment`() {
        assertEquals("Grandma's 80th birthday", Comments.normalize("  Grandma's 80th birthday "))
    }

    @Test
    fun `Comment with a line break`() {
        assertEquals("Grandma's birthday", Comments.normalize("Grandma's\nbirthday"))
        assertEquals("Grandma's birthday", Comments.normalize("Grandma's\r\n\tbirthday"), "A run becomes one space")
        assertEquals("a b", Comments.normalize("\u0001a\u0007b\u0000"), "Other control characters too")
    }

    @Test
    fun `Remove comment`() {
        assertNull(Comments.normalize(null))
        assertNull(Comments.normalize(""))
        assertNull(Comments.normalize("    "))
        assertNull(Comments.normalize(" \n\t "))
    }

    @Test
    fun `500 characters are counted as code points, not UTF-16 units`() {
        // 500 emoji are 1000 UTF-16 units.
        val emoji = "🎂".repeat(500)
        assertEquals(1000, emoji.length)
        assertEquals(emoji, Comments.normalize(emoji))
        assertThrows<ValidationException> { Comments.normalize(emoji + "🎂") }
    }

    @Test
    fun `More than 500 characters after trimming are rejected for the field comment`() {
        assertEquals("a".repeat(500), Comments.normalize("   " + "a".repeat(500) + "   "))
        val e = assertThrows<ValidationException> { Comments.normalize("a".repeat(501)) }
        assertEquals(setOf("comment"), e.fields.keys)
    }
}
