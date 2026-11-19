package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.PhotoIndexStore
import io.github.tycho991.openframe.core.library.StoredIndex
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.sources.Connection
import io.github.tycho991.openframe.core.sources.SourceConfigStore
import io.github.tycho991.openframe.core.sources.SourceException
import io.github.tycho991.openframe.core.sources.SourceRegistry
import io.github.tycho991.openframe.core.sources.StoredSource
import io.github.tycho991.openframe.core.sources.StoredSources
import io.github.tycho991.openframe.core.sources.StoredSyncResults
import io.github.tycho991.openframe.core.sources.SyncResultStore
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The registry with the real HTTP clients against the fake server, in real time: spec `remote-sources`, "Bounded
 * downloads" › Source removed during a sync, and "Rejected access" › Removed original wanted.
 */
class RealSourcesTest {
    @TempDir
    lateinit var dir: File

    private val nextcloud = FakeNextcloud()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val log = AppLog(TestClock())
    private val clients by lazy { OkHttpNextcloudClients { NextcloudHttp(NextcloudHttp.defaultClient(readTimeoutSeconds = 10)) } }
    private val configs by lazy {
        SourceConfigStore(JsonStore(File(dir, "sources.json"), StoredSources.serializer(), TestScope(), log)).also { it.load() }
    }
    private val index by lazy {
        PhotoIndexStore(JsonStore(File(dir, "index.json"), StoredIndex.serializer(), TestScope(), log)).also { it.load() }
    }
    private val registry by lazy {
        SourceRegistry(
            scope,
            configs,
            SyncResultStore(JsonStore(File(dir, "status.json"), StoredSyncResults.serializer(), TestScope(), log)),
            listOf(NextcloudAccountKind(clients)),
            index,
            JvmMediaEngine(),
            File(dir, "sources"),
            {},
            TestClock(),
            log,
        ).also { it.start() }
    }

    @BeforeEach
    fun start() = nextcloud.start()

    @AfterEach
    fun stop() {
        scope.cancel()
        nextcloud.stop()
    }

    private fun account(
        id: String,
        folder: String,
    ) = registry.add(
        StoredSource(
            id,
            NextcloudAccountKind.ID,
            id,
            Connection.Account(nextcloud.baseUrl, nextcloud.userId, nextcloud.loginName, nextcloud.appPassword),
            folder = folder,
        ),
    )

    private fun waitUntil(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "$what did not happen" }
            Thread.sleep(10)
        }
    }

    private fun synced(id: String) = registry.status(id).let { !it.syncing && !it.waiting && it.last.lastSyncOk == true }

    private fun parts() = File(dir, "sources").walk().filter { it.name.endsWith(".part") }.toList()

    /** Sends the bodies that [appliesTo] 64 bytes at a time with a pause: a server that holds them open. */
    private fun hold(appliesTo: (String, String) -> Boolean) {
        nextcloud.pacing = FakeNextcloud.Pacing(64, appliesTo) { Thread.sleep(20) }
    }

    @Test
    fun `Removing a source ends its listing and then its download, and the next source syncs`() {
        nextcloud.put("B/b.jpg", Fixtures.file("landscape.jpg").readBytes(), "eb")
        // A listing of A that would take minutes.
        nextcloud.listingOverride = { dir ->
            if (dir == "A") {
                """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">""" + " ".repeat(256 * 1024) + "</d:multistatus>"
            } else {
                null
            }
        }
        hold { method, path -> method == "PROPFIND" && path.endsWith("/A") }
        account("sa", "/A")
        waitUntil("the listing of A") { nextcloud.requests.any { it == "PROPFIND /remote.php/dav/files/anna/A" } }
        account("sb", "/B")
        waitUntil("B waiting for A") { registry.status("sb").waiting }
        assertTrue(registry.remove("sa"))
        waitUntil("the sync of B after the listing of A ended") { synced("sb") }
        assertEquals(1, index.library.value.photos.size)

        // Now a download that would take minutes.
        nextcloud.listingOverride = null
        nextcloud.put("A/a.jpg", Fixtures.file("landscape.jpg").readBytes() + ByteArray(500_000), "ea")
        hold { method, path -> method == "GET" && path.endsWith("/A/a.jpg") }
        account("sc", "/A")
        waitUntil("the download of A") { nextcloud.requests.any { it == "GET /remote.php/dav/files/anna/A/a.jpg" } }
        nextcloud.put("B/b2.jpg", Fixtures.file("landscape.jpg").readBytes(), "eb2")
        assertTrue(registry.requestSync("sb") || registry.status("sb").waiting)
        assertTrue(registry.remove("sc"))
        waitUntil("the sync of B after the download of A ended") { synced("sb") && index.library.value.photos.size == 2 }
        assertEquals(emptyList<File>(), parts(), "no partial file stays")
        assertFalse(File(dir, "sources/sc").exists())
    }

    /** Spec "Refetched original matches the index" › Replaced on the server. */
    @Test
    fun `A rendering of an original replaced on the server fails and the source syncs soon, once`() {
        nextcloud.put("P/a.jpg", Fixtures.file("landscape.jpg").readBytes(), "e1")
        account("sa", "/P")
        waitUntil("the first sync") { synced("sa") }
        val entry =
            index.library.value.photos.values
                .single()
        registry
            .source("sa")!!
            .originals
            .fileOf(entry)
            .delete()
        nextcloud.files.getValue("P/a.jpg").apply {
            content = Fixtures.file("rotated6.jpg").readBytes()
            etagHeader = "\"e2\""
        }

        fun listings() = synchronized(nextcloud.requests) { nextcloud.requests.count { it.startsWith("PROPFIND") } }
        val before = listings()
        assertEquals(SourceException.Kind.CHANGED, assertThrows(SourceException::class.java) { registry.original(entry) }.kind)
        assertEquals(SourceException.Kind.CHANGED, assertThrows(SourceException::class.java) { registry.original(entry) }.kind)
        waitUntil("the sync after the stale original") { listings() > before && synced("sa") }
        Thread.sleep(3_000)
        assertEquals(before + 1, listings(), "one sync for both renderings")
        val after =
            index.library.value.photos.values
                .single()
        assertEquals(entry.version + 1, after.version, "the sync took the new file")
    }

    @Test
    fun `A source whose access was refused fetches no original for a rendering`() {
        nextcloud.put("P/a.jpg", Fixtures.file("landscape.jpg").readBytes(), "e1")
        account("sa", "/P")
        waitUntil("the first sync") { synced("sa") }
        val entry: PhotoEntry =
            index.library.value.photos.values
                .single()
        // The app password was revoked on the server.
        nextcloud.acceptedAppPassword = "another"
        assertTrue(registry.requestSync("sa"))
        waitUntil("the refused sync") { registry.status("sa").last.rejected }
        registry
            .source("sa")!!
            .originals
            .fileOf(entry)
            .delete()
        val before = synchronized(nextcloud.requests) { nextcloud.requests.size }
        val failure = assertThrows(SourceException::class.java) { registry.original(entry) }
        assertEquals(SourceException.Kind.UNAUTHORIZED, failure.kind)
        assertEquals(before, synchronized(nextcloud.requests) { nextcloud.requests.size }, "no request with the refused password")
    }
}
