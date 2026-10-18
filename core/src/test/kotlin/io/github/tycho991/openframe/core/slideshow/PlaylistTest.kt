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

/** Spec `slideshow-display`: Reihenfolge, Umlauf, Änderungen ohne Neustart. */
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
    fun `Shuffle ohne Wiederholung im Durchgang`() {
        val p = Playlist(Random(System.nanoTime()))
        p.update(ten, Order.SHUFFLE)
        val shown = mutableListOf(p.current!!)
        repeat(9) { shown += p.next()!! }
        assertEquals(ten.toSet(), shown.toSet(), "In den ersten 10 Wechseln jedes Foto genau einmal")
        // Auch der zweite Durchgang ist vollständig und beginnt nicht mit dem letzten Foto.
        val second = mutableListOf(p.next()!!)
        assertNotEquals(shown.last(), second.first())
        repeat(9) { second += p.next()!! }
        assertEquals(ten.toSet(), second.toSet())
    }

    @Test
    fun `Chronologisch und Umlauf`() {
        val p = Playlist()
        p.update(ten.shuffled(), Order.CHRONOLOGICAL)
        val shown = mutableListOf(p.current!!)
        repeat(9) { shown += p.next()!! }
        assertEquals(ten, shown, "aufsteigend nach Aufnahmedatum")
        assertEquals(ten.first(), p.next(), "nach dem letzten wieder das erste")
    }

    @Test
    fun `Vor und zurueck`() {
        val p = Playlist()
        p.update(ten, Order.CHRONOLOGICAL)
        assertEquals(ten[1], p.next())
        assertEquals(ten[0], p.previous())
        assertEquals(ten[9], p.previous(), "zurück über den Anfang")
    }

    @Test
    fun `Zurueck im Shuffle ueber die Durchgangsgrenze`() {
        val p = Playlist(Random(1))
        p.update(ten, Order.SHUFFLE)
        repeat(9) { p.next() }
        val lastOfPass = p.current
        p.next()
        assertEquals(lastOfPass, p.previous())
    }

    @Test
    fun `Neues Foto erscheint noch im laufenden Durchgang`() {
        val p = Playlist(Random(3))
        p.update(ten, Order.SHUFFLE)
        repeat(3) { p.next() }
        val extra = photo(11)
        p.update(ten + extra, Order.SHUFFLE)
        val rest = generateSequence { p.next() }.take(7).toList()
        assertTrue(extra in rest)
    }

    @Test
    fun `Album ausgeblendet`() {
        val urlaub = (1..3).map { photo(it, "urlaub") }
        val andere = (4..8).map { photo(it, "andere") }
        val p = Playlist(Random(5))
        p.update(urlaub + andere, Order.SHUFFLE)
        p.update(andere, Order.SHUFFLE)
        val shown = generateSequence { p.next() }.take(15).toList()
        assertTrue(shown.none { it in urlaub }, "ab dem nächsten Wechsel kein Foto dieses Albums")
    }

    @Test
    fun `Aktuelles Foto geloescht`() {
        val p = Playlist()
        p.update(ten, Order.CHRONOLOGICAL)
        p.next()
        p.next()
        assertEquals(ten[2], p.current)
        val removed = p.update(ten - ten[2], Order.CHRONOLOGICAL)
        assertTrue(removed)
        assertEquals(ten[3], p.current, "das nächste Foto wird zum aktuellen")
        assertFalse(p.update(ten - ten[2] - ten[7], Order.CHRONOLOGICAL))
    }

    @Test
    fun `Weggefallenes aktuelles Foto ueberspringt beim naechsten Wechsel keines`() {
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
    fun `Reihenfolge umstellen behaelt das aktuelle Foto`() {
        val p = Playlist(Random(9))
        p.update(ten, Order.CHRONOLOGICAL)
        repeat(4) { p.next() }
        p.update(ten, Order.SHUFFLE)
        assertEquals(ten[4], p.current)
        val shown = listOf(p.current!!) + generateSequence { p.next() }.take(9).toList()
        assertEquals(ten.toSet(), shown.toSet())
    }

    @Test
    fun `Leer und ein Foto`() {
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
    fun `Vorschau der naechsten Fotos`() {
        val p = Playlist()
        p.update(ten, Order.CHRONOLOGICAL)
        repeat(8) { p.next() }
        assertEquals(listOf(ten[9], ten[0], ten[1]), p.upcoming(3))
        assertEquals(ten[9], p.next(), "upcoming wechselt nicht")
    }
}
