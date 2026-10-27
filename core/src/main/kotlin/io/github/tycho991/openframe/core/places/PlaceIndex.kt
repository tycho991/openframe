package io.github.tycho991.openframe.core.places

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.readFully
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Names the place of a position offline, from the GeoNames places of `cities500` under `places/` on the class path
 * (spec `photo-library`, "Place name from the position"). The format is described in `scripts/build-places.py`.
 *
 * The base loads on the first lookup into flat arrays, about 5 MB of heap without one object per place; a frame
 * that never asks for a place never loads it. Name packs load per language on first use.
 */
class PlaceIndex(
    private val log: AppLog,
    private val open: (String) -> InputStream? = { PlaceIndex::class.java.getResourceAsStream("/places/$it") },
) {
    private val base: Base? by lazy { loadBase() }
    private val packs = HashMap<String, NamePack?>()

    /** The nearest place within [MAX_DISTANCE_KM] of the position, or `null`. */
    fun nearest(
        lat: Double,
        lon: Double,
    ): Place? {
        val b = base ?: return null
        val cosLat = cos(Math.toRadians(lat))
        var best = -1
        var bestDistance = MAX_DISTANCE_KM * MAX_DISTANCE_KM
        val rowFrom = max(cellRow(lat - SEARCH_DEGREES), 0)
        val rowTo = min(cellRow(lat + SEARCH_DEGREES), ROWS - 1)
        // Longitude degrees shrink towards the poles: the width that still covers 50 km in the row nearest a pole.
        val edgeLat = max(abs(lat - SEARCH_DEGREES), abs(lat + SEARCH_DEGREES)).coerceAtMost(90.0)
        val edgeCos = cos(Math.toRadians(edgeLat))
        val lonDegrees = if (edgeCos * 180 <= SEARCH_DEGREES) 180.0 else SEARCH_DEGREES / edgeCos
        val colFrom = floor(lon - lonDegrees).toInt() + 180
        val colTo = if (lonDegrees >= 180) colFrom + COLUMNS - 1 else floor(lon + lonDegrees).toInt() + 180
        for (row in rowFrom..rowTo) {
            val rowLat = (row - 90).toDouble()
            for (c in colFrom..min(colTo, colFrom + COLUMNS - 1)) {
                val col = Math.floorMod(c, COLUMNS)
                val colLon = (col - 180).toDouble()
                val cell = row * COLUMNS + col
                for (i in b.cells[cell] until b.cells[cell + 1]) {
                    val dLat = rowLat + b.lat[i] / UNITS - lat
                    var dLon = colLon + b.lon[i] / UNITS - lon
                    if (dLon > 180) {
                        dLon -= 360
                    } else if (dLon < -180) {
                        dLon += 360
                    }
                    // Equirectangular: exact enough for 50 km.
                    val y = dLat * KM_PER_DEGREE
                    val x = dLon * KM_PER_DEGREE * cosLat
                    val d = x * x + y * y
                    // Equally far: the lower index, so the answer does not depend on the order of the cells.
                    if (d < bestDistance || (d == bestDistance && (best < 0 || i < best))) {
                        best = i
                        bestDistance = d
                    }
                }
            }
        }
        if (best < 0) return null
        return Place(best, b.name(best), b.countries[b.country[best].toInt() and 0xFF])
    }

    /**
     * "Place, country" in [locale] for the position, or `null` without a place within 50 km (spec "Language of the
     * place name"). The place takes the name of the language's pack if it has one; a country the platform cannot
     * name, for which [Locale.getDisplayCountry] returns the code itself, is left out.
     */
    fun placeName(
        lat: Double,
        lon: Double,
        locale: Locale,
    ): String? {
        val place = nearest(lat, lon) ?: return null
        return label(pack(locale.language)?.name(place.index) ?: place.name, place.country, locale)
    }

    private fun pack(language: String): NamePack? {
        val b = base ?: return null
        return synchronized(packs) { packs.getOrPut(language) { loadPack(language, b.datasetId) } }
    }

    private fun loadBase(): Base? {
        val stream = read("base.bin")
        if (stream == null) {
            log.w(TAG, "No place data on the class path")
            return null
        }
        return try {
            stream.use { input ->
                expect(input, BASE_MAGIC)
                val datasetId = input.readLong()
                val n = input.readInt()
                val countryCount = input.readUnsignedShort()
                val countries =
                    Array(countryCount) {
                        val code = ByteArray(2).also { input.readFully(it) }
                        if (code[0] == 0.toByte()) "" else String(code, Charsets.US_ASCII)
                    }
                val cells = ints(input, CELLS + 1)
                val lat = shorts(input, n)
                val lon = shorts(input, n)
                val country = ByteArray(n).also { input.readFully(it) }
                val nameOffsets = ints(input, n)
                val pool = ByteArray(input.readInt()).also { input.readFully(it) }
                if (cells[CELLS] != n) throw IOException("cell table ends at ${cells[CELLS]}, not at $n")
                log.i(TAG, "Loaded $n places")
                Base(datasetId, countries, cells, lat, lon, country, nameOffsets, pool)
            }
        } catch (e: IOException) {
            log.e(TAG, "Place data could not be read", e)
            null
        }
    }

    private fun loadPack(
        language: String,
        datasetId: Long,
    ): NamePack? =
        try {
            read("names-$language.bin")?.use { input ->
                expect(input, PACK_MAGIC)
                val id = input.readLong()
                val code = ByteArray(2).also { input.readFully(it) }
                if (id != datasetId) {
                    log.w(TAG, "Name pack $language belongs to dataset ${hex(id)}, not ${hex(datasetId)}; it is ignored")
                    return@use null
                }
                val m = input.readInt()
                val indices = IntArray(m)
                val offsets = IntArray(m + 1)
                val pool = ByteArrayOutputStream()
                for (k in 0 until m) {
                    indices[k] = input.readInt()
                    val bytes = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
                    pool.write(bytes)
                    offsets[k + 1] = pool.size()
                }
                log.i(TAG, "Loaded $m names for ${String(code, Charsets.US_ASCII)}")
                NamePack(indices, offsets, pool.toByteArray())
            }
        } catch (e: IOException) {
            log.e(TAG, "Name pack $language could not be read", e)
            null
        }

    private fun read(file: String): DataInputStream? = open(file)?.let { DataInputStream(BufferedInputStream(GZIPInputStream(it, BUFFER))) }

    private fun expect(
        input: DataInputStream,
        magic: String,
    ) {
        val bytes = ByteArray(magic.length).also { input.readFully(it) }
        if (String(bytes, Charsets.US_ASCII) != magic) throw IOException("not a $magic file")
        val version = input.readUnsignedShort()
        if (version != FORMAT_VERSION) throw IOException("$magic version $version, expected $FORMAT_VERSION")
    }

    private fun ints(
        input: InputStream,
        count: Int,
    ): IntArray = IntArray(count).also { ByteBuffer.wrap(bytes(input, count * 4)).asIntBuffer().get(it) }

    private fun shorts(
        input: InputStream,
        count: Int,
    ): ShortArray = ShortArray(count).also { ByteBuffer.wrap(bytes(input, count * 2)).asShortBuffer().get(it) }

    private fun bytes(
        input: InputStream,
        count: Int,
    ): ByteArray {
        val buf = ByteArray(count)
        if (input.readFully(buf) != count) throw IOException("place data ends early")
        return buf
    }

    /** A place of the base: its index, GeoNames' own name and the country code (empty without one). */
    data class Place(
        val index: Int,
        val name: String,
        val country: String,
    )

    private class Base(
        val datasetId: Long,
        val countries: Array<String>,
        val cells: IntArray,
        val lat: ShortArray,
        val lon: ShortArray,
        val country: ByteArray,
        val nameOffsets: IntArray,
        val pool: ByteArray,
    ) {
        fun name(i: Int): String {
            val end = if (i + 1 < nameOffsets.size) nameOffsets[i + 1] else pool.size
            return String(pool, nameOffsets[i], end - nameOffsets[i], Charsets.UTF_8)
        }
    }

    private class NamePack(
        private val indices: IntArray,
        private val offsets: IntArray,
        private val pool: ByteArray,
    ) {
        fun name(place: Int): String? {
            val k = indices.binarySearch(place)
            if (k < 0) return null
            return String(pool, offsets[k], offsets[k + 1] - offsets[k], Charsets.UTF_8)
        }
    }

    companion object {
        const val MAX_DISTANCE_KM = 50.0
        private const val KM_PER_DEGREE = 6371.0 * Math.PI / 180
        private const val SEARCH_DEGREES = 0.45
        private const val UNITS = 10_000.0
        private const val ROWS = 180
        private const val COLUMNS = 360
        private const val CELLS = ROWS * COLUMNS
        private const val FORMAT_VERSION = 1
        private const val BASE_MAGIC = "OFPB"
        private const val PACK_MAGIC = "OFPN"
        private const val BUFFER = 64 * 1024
        private const val TAG = "places"

        /** "[name], country" with the country of [code] named in [locale]; [name] alone if there is no such name. */
        fun label(
            name: String,
            code: String,
            locale: Locale,
        ): String {
            if (code.isEmpty()) return name
            val country = Locale("", code).getDisplayCountry(locale)
            return if (country.isEmpty() || country.equals(code, ignoreCase = true)) name else "$name, $country"
        }

        private fun cellRow(lat: Double) = floor(lat).toInt() + 90

        private fun hex(id: Long) = java.lang.Long.toHexString(id)
    }
}
