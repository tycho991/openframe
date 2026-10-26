package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.testing.TestFrame
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
                        PhotoEntry.idFor("local", path),
                        "local",
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

    /** Five pages of each query after warming up: median under 50 ms, none over 200 ms. */
    private fun assertPagesFast(queries: List<String>) {
        repeat(5) { queries.forEach { frame.get("/api/v1/photos$it") } }

        val times = mutableListOf<Long>()
        for (query in queries) {
            var cursor: String? = null
            repeat(5) {
                val q = if (cursor == null) query else (if (query.isEmpty()) "?" else "$query&") + "cursor=$cursor"
                val start = System.nanoTime()
                val res = frame.get("/api/v1/photos$q")
                times += (System.nanoTime() - start) / 1_000_000
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
}
