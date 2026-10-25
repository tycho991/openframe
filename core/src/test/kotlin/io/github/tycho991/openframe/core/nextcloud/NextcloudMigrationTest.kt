package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.api.PhotoPageDto
import io.github.tycho991.openframe.core.api.SourceListDto
import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.StoredIndex
import io.github.tycho991.openframe.core.sources.StoredSyncResult
import io.github.tycho991.openframe.core.store.StoreJson
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.TestFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `remote-sources`: Existing connection after the update. A frame of 0.1.0 with a Nextcloud connection starts with this build. */
class NextcloudMigrationTest {
    @TempDir
    lateinit var dir: File

    private val clients = FakeClients()
    private val path = "holiday/a.jpg"
    private val photoId = PhotoEntry.idFor("nextcloud", path)
    private val state get() = File(dir, "state")
    private val cache get() = File(dir, "cache")
    private val original = Fixtures.file("landscape.jpg").readBytes()

    /** What 0.1.0 left: `nextcloud.json`, `nextcloud-status.json`, an index with a hidden photo and its cached original. */
    private fun oldFrame(connected: Boolean = true) {
        state.mkdirs()
        val old =
            StoredNextcloud(
                serverUrl = "https://cloud.example.org",
                userId = "anna",
                loginName = "Anna",
                appPassword = if (connected) "app-pw-old-Tz55" else "",
                folder = "/Photos",
                removeVanished = false,
                intervalMinutes = 45,
                originalsBudgetMb = 500,
            )
        File(state, "nextcloud.json").writeText(StoreJson.encodeToString(StoredNextcloud.serializer(), old))
        File(state, "nextcloud-status.json").writeText(
            StoreJson.encodeToString(StoredSyncResult.serializer(), StoredSyncResult(1_760_000_000_000L, true, null, 1, 0, 0)),
        )
        val entry = PhotoEntry(photoId, "nextcloud", path, 400, 300, 0, original.size.toLong(), 1, 1, hidden = true, etag = "e1")
        File(state, "index.json").writeText(StoreJson.encodeToString(StoredIndex.serializer(), StoredIndex(photos = listOf(entry))))
        File(cache, "nextcloud").mkdirs()
        File(cache, "nextcloud/$photoId.jpg").writeBytes(original)
    }

    private fun TestFrame.sources() = ApiJson.decodeFromString(SourceListDto.serializer(), get("/api/v1/sources").body()).sources

    private fun TestFrame.photos() = ApiJson.decodeFromString(PhotoPageDto.serializer(), get("/api/v1/photos").body()).items

    @Test
    fun `The connection becomes the source nextcloud with its settings, photo IDs, hidden state and originals`() {
        oldFrame()
        TestFrame(dir, nextcloudClients = clients).use { frame ->
            val source = frame.sources().single()
            assertEquals("nextcloud", source.id)
            assertEquals("nextcloud-account", source.kind)
            assertEquals("cloud.example.org", source.settings.name)
            assertEquals("Anna", source.user)
            assertEquals("https://cloud.example.org", source.serverUrl)
            assertEquals("/Photos", source.settings.folder)
            assertEquals(45, source.settings.intervalMinutes)
            assertEquals(500, source.settings.originalsBudgetMb)
            assertFalse(source.settings.removeVanished)
            assertEquals(true, source.status.ok)
            assertEquals(1, source.status.added)

            val photo = frame.photos().single()
            assertEquals(photoId, photo.id)
            assertTrue(photo.hidden)
            assertEquals("nextcloud", photo.source)

            assertTrue(File(cache, "sources/nextcloud/$photoId.jpg").isFile, "the original moved")
            assertFalse(File(cache, "nextcloud").exists())
            assertTrue(File(state, "nextcloud.json").isFile, "the old file stays for going back")
            assertTrue(File(state, "sources.json").isFile)

            // Rendering takes the moved original; a sync needs no new login and downloads nothing that did not change.
            assertEquals(200, frame.get("/api/v1/photos/$photoId/image?size=thumb").statusCode())
            clients.remote.put(path, original, "e1")
            assertEquals(202, frame.send("POST", "/api/v1/sources/nextcloud/sync").statusCode())
            val deadline = System.currentTimeMillis() + 10_000
            while (frame
                    .sources()
                    .single()
                    .status.syncing
            ) {
                check(System.currentTimeMillis() < deadline) { "the sync did not end" }
                Thread.sleep(25)
            }
            assertEquals(
                true,
                frame
                    .sources()
                    .single()
                    .status.ok,
            )
            assertEquals(emptyList<String>(), clients.remote.downloads)
            assertEquals(listOf(photoId to true), frame.photos().map { it.id to it.hidden })
        }
    }

    @Test
    fun `The migration runs once, and removing the source deletes the old file`() {
        oldFrame()
        TestFrame(dir, nextcloudClients = clients).close()
        TestFrame(dir, nextcloudClients = clients).use { frame ->
            assertEquals(listOf("nextcloud"), frame.sources().map { it.id }, "not migrated a second time")
            assertEquals(204, frame.send("DELETE", "/api/v1/sources/nextcloud").statusCode())
            assertEquals(1, (clients.login as FakeLoginFlow).revoked)
            assertFalse(File(state, "nextcloud.json").exists())
            assertFalse(File(state, "nextcloud-status.json").exists())
            assertEquals(emptyList<Any>(), frame.photos())
            assertFalse(File(cache, "sources/nextcloud").exists())
        }
        TestFrame(dir, nextcloudClients = clients).use { frame ->
            assertEquals(emptyList<Any>(), frame.sources(), "a removed source does not come back")
        }
    }

    @Test
    fun `A frame that was disconnected has no source after the update`() {
        oldFrame(connected = false)
        TestFrame(dir, nextcloudClients = clients).use { frame ->
            assertEquals(emptyList<Any>(), frame.sources())
        }
    }
}
