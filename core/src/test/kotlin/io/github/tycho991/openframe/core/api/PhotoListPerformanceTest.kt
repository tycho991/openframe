package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.testing.Res
import io.github.tycho991.openframe.core.testing.TestFrame
import io.github.tycho991.openframe.core.testing.TestHttp
import io.github.tycho991.openframe.core.testing.bestOfMs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `photo-library`: Photo list through the API, 10,000 photos without noticeable delay. */
class PhotoListPerformanceTest {
    @TempDir
    lateinit var dir: File

    private val frame by lazy { TestFrame(dir) }

    @AfterEach
    fun tearDown() = frame.close()

    private fun putPhotos(pathOf: (Int) -> String) {
        frame.core.library.index.edit { edit ->
            for (i in 0 until 10_000) {
                val path = pathOf(i)
                edit.put(
                    PhotoEntry(
                        PhotoEntry.idFor(SOURCE, path),
                        SOURCE,
                        path,
                        4000,
                        3000,
                        1_600_000_000_000 + i * 60_000L,
                        3_000_000,
                        1,
                        1,
                        i % 7 == 0,
                    ),
                )
            }
        }
    }

    /**
     * Five pages of each query after warming up: median under 50 ms, none over 200 ms. Each page counts with the
     * fastest of three requests, so a GC pause or a busy CI runner does not fail the test, a slow handler does.
     */
    private fun assertPagesFast(queries: List<String>) {
        repeat(5) { queries.forEach { frame.get("/api/v1/photos$it") } }

        val times = mutableListOf<Long>()
        for (query in queries) {
            var cursor: String? = null
            repeat(5) {
                val q = if (cursor == null) query else (if (query.isEmpty()) "?" else "$query&") + "cursor=$cursor"
                lateinit var res: Res
                times += bestOfMs(3) { res = frame.get("/api/v1/photos$q") }
                assertEquals(200, res.statusCode())
                cursor = ApiJson.decodeFromString(PhotoPageDto.serializer(), res.body()).nextCursor
            }
        }
        val median = times.sorted()[times.size / 2]
        assertTrue(median < 50, "Median $median ms, all: $times")
        assertTrue(times.max() < 200, "Outliers: $times")
    }

    @Test
    fun `10000 entries under 50 ms per page`() {
        putPhotos { i -> "album${i % 20}/foto$i.jpg" }
        assertPagesFast(listOf("", "?album=album3", "?visible=false&sort=name_asc", "?sort=date_asc&limit=200"))
    }

    /** `nested-albums`: visibility and the album filter in four levels, with exceptions, in the same time. */
    @Test
    fun `10000 entries in four album levels under 50 ms per page`() {
        putPhotos { i -> "level${i % 5}/sub${i % 7}/deeper${i % 11}/deepest${i % 13}/foto$i.jpg" }
        frame.core.library.index.edit {
            it.setAlbumHidden("level1", true)
            it.setAlbumHidden("level1/sub2", false)
            it.setAlbumHidden("level1/sub2/deeper3", true)
        }
        assertPagesFast(
            listOf(
                "?visible=true",
                "?visible=false&sort=name_asc",
                "?album=level1",
                "?album=level1/sub2&visible=true",
                "?album=level2/sub1&subalbums=false",
            ),
        )
    }

    /**
     * `album-groups-in-photos`: the album list with counts and one signed cover per album, about 5,400 albums, within
     * the budget of a page. Each request follows a change of the library, so it builds the album summary again, as
     * after every scan or switch. The answer is checked against the contract once, outside the timing, since
     * checking a body of thousands of albums takes longer than the frame does.
     */
    @Test
    fun `Album list of 10000 entries in four album levels under 50 ms`() {
        putPhotos { i -> "level${i % 5}/sub${i % 7}/deeper${i % 11}/deepest${i % 13}/foto$i.jpg" }
        frame.core.library.index.edit {
            it.setAlbumHidden("level1", true)
            it.setAlbumHidden("level1/sub2", false)
            it.setAlbumHidden("level1/sub2/deeper3", true)
        }
        val albums = ApiJson.decodeFromString(AlbumListDto.serializer(), frame.get("/api/v1/albums").body()).albums
        assertTrue(albums.size > 5000, "${albums.size} albums")

        val probe = PhotoEntry.idFor(SOURCE, "level0/sub0/deeper0/deepest0/foto0.jpg")
        TestHttp("http://127.0.0.1:${frame.core.server.boundPort}", checkContract = false).use { http ->
            fun timed(): Long {
                var best = Long.MAX_VALUE
                repeat(3) {
                    frame.core.library.index
                        .edit { e -> e.put(e.get(probe)!!.let { it.copy(hidden = !it.hidden) }) }
                    val start = System.nanoTime()
                    val res = http.get("/api/v1/albums", frame.auth)
                    best = minOf(best, (System.nanoTime() - start) / 1_000_000)
                    assertEquals(200, res.statusCode())
                }
                return best
            }
            repeat(5) { timed() }
            val times = List(10) { timed() }
            val median = times.sorted()[times.size / 2]
            assertTrue(median < 50, "Median $median ms, all: $times")
            assertTrue(times.max() < 200, "Outliers: $times")
        }
    }

    private companion object {
        /**
         * Not the local source: its scan runs every 200 ms in a [TestFrame] and would remove the entries, which have
         * no file, in the middle of the measurement.
         */
        const val SOURCE = "nextcloud"
    }
}
