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

/** Spec `photo-library`: Fotoliste über die API, 10.000 Fotos ohne spürbare Verzögerung. */
class PhotoListPerformanceTest {
    @TempDir
    lateinit var dir: File

    private val frame by lazy { TestFrame(dir) }

    @AfterEach
    fun tearDown() = frame.close()

    @Test
    fun `10000 Eintraege unter 50 ms pro Seite`() {
        frame.core.library.index.edit { edit ->
            for (i in 0 until 10_000) {
                val path = "album${i % 20}/foto$i.jpg"
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
        val queries = listOf("", "?album=album3", "?visible=false&sort=name_asc", "?sort=date_asc&limit=200")
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
        assertTrue(median < 50, "Median $median ms, alle: $times")
        assertTrue(times.max() < 200, "Ausreißer: $times")
    }
}
