package io.github.tycho991.openframe.core.places

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.log.LogLevel
import io.github.tycho991.openframe.core.testing.TestClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Spec `photo-library`, requirements "Place name from the position" and "Language of the place name". */
class PlaceIndexTest {
    private val log = AppLog(TestClock())
    private val places = PlaceIndex(log)

    @Test
    fun `Photo from Siena is named Siena, Italy in English`() {
        assertEquals("Siena, Italy", places.placeName(43.3188, 11.3308, Locale.ENGLISH))
    }

    @Test
    fun `Photo on the open sea has no place name`() {
        assertNull(places.nearest(30.0, -40.0))
        assertNull(places.placeName(30.0, -40.0, Locale.ENGLISH))
    }

    @Test
    fun `Milan in German is Mailand, Italien`() {
        assertEquals("Mailand, Italien", places.placeName(45.4643, 9.1895, Locale.GERMAN))
    }

    @Test
    fun `Milan in English is Milan, Italy`() {
        assertEquals("Milan, Italy", places.placeName(45.4643, 9.1895, Locale.ENGLISH))
    }

    @Test
    fun `A language without a pack uses GeoNames' own names and the country in that language`() {
        assertEquals("Milan, Italie", places.placeName(45.4643, 9.1895, Locale.FRENCH))
    }

    @Test
    fun `The nearest place is found in the next cell`() {
        // Longyearbyen lies at 78.2233° N, 15.6469° E, about 19 km away and one cell further west, where a degree of
        // longitude is only 23 km wide.
        assertEquals("Longyearbyen", places.nearest(78.22, 16.5)?.name)
    }

    @Test
    fun `The search crosses the date line`() {
        // Egvekinot lies at 66.3232° N, 179.1184° W, about 42 km east of the position across the date line.
        assertEquals("Egvekinot", places.nearest(66.3, 179.95)?.name)
    }

    @Test
    fun `Nothing farther than 50 km counts`() {
        // Savusavu (16.7794° S, 179.3356° E) is the nearest place, about 71 km away.
        assertNull(places.nearest(-16.8, 179.999))
    }

    @Test
    fun `The poles and the edges of the grid do not fail`() {
        assertNull(places.nearest(90.0, 0.0))
        places.nearest(-90.0, 180.0)
        places.nearest(0.0, -180.0)
        places.nearest(89.99, 179.99)
    }

    @Test
    fun `A pack of another dataset is ignored and logged`() {
        val other =
            PlaceIndex(log) { file ->
                val bytes = resource(file)
                if (file == "names-de.bin") ByteArrayInputStream(withOtherDatasetId(bytes)) else ByteArrayInputStream(bytes)
            }
        assertEquals("Milan, Italien", other.placeName(45.4643, 9.1895, Locale.GERMAN))
        assertTrue(log.recent(100).any { it.level == LogLevel.WARN && it.message.contains("Name pack de belongs to dataset") })
    }

    @Test
    fun `Without place data there are no place names`() {
        val none = PlaceIndex(log) { null }
        assertNull(none.placeName(45.4643, 9.1895, Locale.GERMAN))
    }

    @Test
    fun `10,000 lookups take under one second`() {
        places.nearest(0.0, 0.0)
        val random = java.util.Random(7)
        val start = System.nanoTime()
        repeat(10_000) {
            // Mostly Europe, where the places are densest.
            places.nearest(35 + random.nextDouble() * 25, -10 + random.nextDouble() * 40)
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 1000, "10,000 lookups took $ms ms")
    }

    private fun resource(file: String): ByteArray = PlaceIndex::class.java.getResourceAsStream("/places/$file")!!.use { it.readBytes() }

    /** The pack with a different dataset ID in its header (magic 4 bytes, version 2 bytes, then the ID). */
    private fun withOtherDatasetId(gzip: ByteArray): ByteArray {
        val raw = GZIPInputStream(ByteArrayInputStream(gzip)).use { it.readBytes() }
        raw[6] = (raw[6].toInt() xor 0xFF).toByte()
        return ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(raw) } }.toByteArray()
    }
}
