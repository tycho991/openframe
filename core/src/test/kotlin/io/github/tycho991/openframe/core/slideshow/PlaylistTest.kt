package io.github.tycho991.openframe.core.slideshow

import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.settings.Order
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Spec `slideshow-display`: Order, wrap-around, changes without restart. */
class PlaylistTest {
    private fun photo(
        n: Int,
        album: String? = null,
    ): PhotoEntry {
        val path = (album?.let { "$it/" } ?: "") + "p%02d.jpg".format(n)
        return PhotoEntry(PhotoEntry.idFor("local", path), "local", path, 4, 3, n * 1000L, 1, 1, 1)
    }

    private val ten = (1..10).map { photo(it) }

    @RepeatedTest(20)
    fun `Shuffle without repeats within a pass`() {
        val p = Playlist(Random(System.nanoTime()))
        p.update(ten, Order.SHUFFLE)
        val shown = mutableListOf(p.current!!)
        repeat(9) { shown += p.next()!! }
        assertEquals(ten.toSet(), shown.toSet(), "Every photo exactly once in the first 10 changes")
        // The second pass is complete as well and does not begin with the last photo.
        val second = mutableListOf(p.next()!!)
        assertNotEquals(shown.last(), second.first())
        repeat(9) { second += p.next()!! }
        assertEquals(ten.toSet(), second.toSet())
    }

    @Test
    fun `Chronological and wrap-around`() {
        val p = Playlist()
        p.update(ten.shuffled(), Order.CHRONOLOGICAL)
        val shown = mutableListOf(p.current!!)
        repeat(9) { shown += p.next()!! }
        assertEquals(ten, shown, "ascending by capture date")
        assertEquals(ten.first(), p.next(), "the first again after the last")
    }

    @Test
    fun `Forward and back`() {
        val p = Playlist()
        p.update(ten, Order.CHRONOLOGICAL)
        assertEquals(ten[1], p.next())
        assertEquals(ten[0], p.previous())
        assertEquals(ten[9], p.previous(), "back across the start")
    }

    @Test
    fun `Back in shuffle across the pass boundary`() {
        val p = Playlist(Random(1))
        p.update(ten, Order.SHUFFLE)
        repeat(9) { p.next() }
        val lastOfPass = p.current
        p.next()
        assertEquals(lastOfPass, p.previous())
    }

    @Test
    fun `New photo still appears in the running pass`() {
        val p = Playlist(Random(3))
        p.update(ten, Order.SHUFFLE)
        repeat(3) { p.next() }
        val extra = photo(11)
        p.update(ten + extra, Order.SHUFFLE)
        val rest = generateSequence { p.next() }.take(7).toList()
        assertTrue(extra in rest)
    }

    @Test
    fun `Album hidden`() {
        val holiday = (1..3).map { photo(it, "holiday") }
        val other = (4..8).map { photo(it, "other") }
        val p = Playlist(Random(5))
        p.update(holiday + other, Order.SHUFFLE)
        p.update(other, Order.SHUFFLE)
        val shown = generateSequence { p.next() }.take(15).toList()
        assertTrue(shown.none { it in holiday }, "no photo of this album from the next change on")
    }

    @Test
    fun `Current photo deleted`() {
        val p = Playlist()
        p.update(ten, Order.CHRONOLOGICAL)
        p.next()
        p.next()
        assertEquals(ten[2], p.current)
        val removed = p.update(ten - ten[2], Order.CHRONOLOGICAL)
        assertTrue(removed)
        assertEquals(ten[3], p.current, "the next photo becomes the current one")
        assertFalse(p.update(ten - ten[2] - ten[7], Order.CHRONOLOGICAL))
    }

    @Test
    fun `Dropped current photo skips none at the next change`() {
        for (order in Order.entries) {
            val p = Playlist(Random(11))
            p.update(ten, order)
            val first = p.current!!
            val second = p.next()!!
            val third = p.upcoming(1).single()
            p.update(ten - second, order)
            assertEquals(third, p.next(), "$order")
            assertTrue(p.current != first)
        }
    }

    @Test
    fun `Changing the order keeps the current photo`() {
        val p = Playlist(Random(9))
        p.update(ten, Order.CHRONOLOGICAL)
        repeat(4) { p.next() }
        p.update(ten, Order.SHUFFLE)
        assertEquals(ten[4], p.current)
        val shown = listOf(p.current!!) + generateSequence { p.next() }.take(9).toList()
        assertEquals(ten.toSet(), shown.toSet())
    }

    @Test
    fun `Empty and one photo`() {
        val p = Playlist()
        p.update(emptyList(), Order.SHUFFLE)
        assertNull(p.current)
        assertNull(p.next())
        assertEquals(emptyList<PhotoEntry>(), p.upcoming(3))
        p.update(listOf(ten[0]), Order.SHUFFLE)
        assertEquals(ten[0], p.next())
        assertEquals(ten[0], p.previous())
    }

    @Test
    fun `Preview of the next photos`() {
        val p = Playlist()
        p.update(ten, Order.CHRONOLOGICAL)
        repeat(8) { p.next() }
        assertEquals(listOf(ten[9], ten[0], ten[1]), p.upcoming(3))
        assertEquals(ten[9], p.next(), "upcoming does not move")
    }
}
