@file:Suppress("ktlint:standard:max-line-length")

package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.library.Crop
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.PhotoIndexStore
import io.github.tycho991.openframe.core.library.ScanResult
import io.github.tycho991.openframe.core.library.StoredIndex
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.SkipReason
import io.github.tycho991.openframe.core.sources.FakeKind
import io.github.tycho991.openframe.core.sources.OriginalsStore
import io.github.tycho991.openframe.core.sources.RemoteSource
import io.github.tycho991.openframe.core.sources.SourceConfigStore
import io.github.tycho991.openframe.core.sources.SourceException
import io.github.tycho991.openframe.core.sources.SourceLimits
import io.github.tycho991.openframe.core.sources.StoredSources
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import okhttp3.Interceptor
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * A server that sends what it should not: spec `nextcloud-source`, "Bounded answers of a server" and "Listings stay
 * inside the folder"; `remote-sources`, "Bounded downloads" and "Undecodable formats are not downloaded". The real
 * client against the fake server, synced by a [RemoteSource].
 */
class HostileServerTest {
    @TempDir
    lateinit var dir: File

    private val nextcloud = FakeNextcloud()
    private val clock = TestClock()
    private val log = AppLog(TestClock())
    private val engine = JvmMediaEngine()

    /** The bytes the client took from the bodies of downloads, counted where the frame reads them. */
    private val downloadedBytes = AtomicLong()

    private val http by lazy {
        val counting =
            Interceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = response.body
                if (chain.request().method != "GET" || body == null) return@Interceptor response
                val source =
                    object : ForwardingSource(body.source()) {
                        override fun read(
                            sink: Buffer,
                            byteCount: Long,
                        ): Long = super.read(sink, byteCount).also { if (it > 0) downloadedBytes.addAndGet(it) }
                    }
                response.newBuilder().body(source.buffer().asResponseBody(body.contentType(), body.contentLength())).build()
            }
        NextcloudHttp(
            NextcloudHttp
                .defaultClient(readTimeoutSeconds = 10)
                .newBuilder()
                .addInterceptor(counting)
                .build(),
            clock,
        )
    }

    private var freeBytes = Long.MAX_VALUE / 2
    private var unknownSizeBytes = SourceLimits.DOWNLOAD_UNKNOWN_SIZE_BYTES

    private val configs: SourceConfigStore by lazy {
        SourceConfigStore(JsonStore(File(dir, "sources.json"), StoredSources.serializer(), TestScope(), log)).also {
            it.load()
            it.add(FakeKind.source("nextcloud"))
        }
    }
    private val index by lazy {
        PhotoIndexStore(JsonStore(File(dir, "index.json"), StoredIndex.serializer(), TestScope(), log)).also { it.load() }
    }
    private val originals by lazy { OriginalsStore(File(dir, "originals"), freeBytes = { freeBytes }) }
    private val folder by lazy {
        WebDavFolder(http, nextcloud.baseUrl, nextcloud.userId, nextcloud.loginName, nextcloud.appPassword, "/Photos")
    }
    private val source by lazy {
        RemoteSource(
            "nextcloud",
            { configs.get("nextcloud") },
            FakeKind { folder },
            index,
            engine,
            originals,
            {},
            log,
            unknownSizeBytes = unknownSizeBytes,
        )
    }

    @BeforeEach
    fun start() = nextcloud.start()

    @AfterEach
    fun stop() = nextcloud.stop()

    private fun sync(): ScanResult = runBlocking { source.scan() }

    private fun failedSync(): SourceException = assertThrows(SourceException::class.java) { sync() }

    private fun photos() =
        index.library.value.photos.values
            .filter { it.source == "nextcloud" }
            .associateBy { it.path }

    private fun jpg() = Fixtures.file("landscape.jpg").readBytes()

    /** A first sync with two photos, then every failure must leave them, also with "remove vanished" on. */
    private fun synced() {
        nextcloud.put("Photos/a.jpg", jpg(), "e1")
        nextcloud.put("Photos/b.jpg", jpg(), "e2")
        assertEquals(ScanResult(2, 0, 0, 0), sync())
        assertTrue(configs.get("nextcloud")!!.removeVanished)
    }

    private fun assertPhotosStay() = assertEquals(setOf("a.jpg", "b.jpg"), photos().keys)

    private val own = "/remote.php/dav/files/anna/Photos/"

    private fun response(
        href: String,
        folder: Boolean = false,
    ) = "<d:response><d:href>$href</d:href><d:propstat><d:prop>" +
        (if (folder) "<d:resourcetype><d:collection/></d:resourcetype>" else "<d:resourcetype/><d:getetag>\"x\"</d:getetag><d:getcontentlength>10</d:getcontentlength>") +
        "</d:prop></d:propstat></d:response>"

    private fun multistatus(vararg responses: String) =
        """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">""" + responses.joinToString("") + "</d:multistatus>"

    // --- Bounded answers of a server

    @Test
    fun `A listing with a DOCTYPE fails the sync as protocol and names the cause`() {
        synced()
        nextcloud.listingOverride = {
            """<?xml version="1.0"?><!DOCTYPE multistatus [<!ELEMENT multistatus ANY>]>""" +
                multistatus(response(own, true)).substringAfter("?>")
        }
        val failure = failedSync()
        assertEquals(SourceException.Kind.PROTOCOL, failure.kind)
        assertEquals(PropfindParser.DOCTYPE_REFUSED, failure.message)
        assertPhotosStay()
    }

    @Test
    fun `A listing with nested entities fails the sync without expanding them`() {
        synced()
        nextcloud.listingOverride = {
            """<?xml version="1.0"?><!DOCTYPE d:multistatus [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">""" +
                """<!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;"><!ENTITY d "&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;">]>""" +
                """<d:multistatus xmlns:d="DAV:"><d:response><d:href>&d;</d:href></d:response></d:multistatus>"""
        }
        val failure = failedSync()
        assertEquals(SourceException.Kind.PROTOCOL, failure.kind)
        assertEquals(PropfindParser.DOCTYPE_REFUSED, failure.message)
        assertPhotosStay()
    }

    @Test
    fun `An element beyond 16 KiB fails the sync as a limit`() {
        synced()
        nextcloud.listingOverride = { multistatus(response(own, true), response(own + "a".repeat(17 * 1024) + ".jpg")) }
        val failure = failedSync()
        assertEquals(SourceException.Kind.LIMIT, failure.kind)
        assertTrue(failure.message!!.contains("16 KiB"), failure.message)
        assertPhotosStay()
    }

    @Test
    fun `More than 50,000 entries in one answer fail the sync as a limit`() {
        synced()
        val many = (0..SourceLimits.ENTRIES_PER_ANSWER).map { response("${own}f$it.txt") }.toTypedArray()
        nextcloud.listingOverride = { multistatus(response(own, true), *many) }
        val failure = failedSync()
        assertEquals(SourceException.Kind.LIMIT, failure.kind)
        assertTrue(failure.message!!.contains("50000 entries"), failure.message)
        assertPhotosStay()
    }

    @Test
    fun `A gzip listing beyond 64 MiB after decompression fails the sync as a limit`() {
        synced()
        // 65 MiB of spaces between the entries, compressed to a few kilobytes on the wire.
        val gzipped = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(gzipped).use { out ->
            out.write(multistatus(response(own, true)).substringBefore("</d:multistatus>").toByteArray())
            val spaces = ByteArray(64 * 1024) { ' '.code.toByte() }
            repeat(65 * 16) { out.write(spaces) }
            out.write("</d:multistatus>".toByteArray())
        }
        nextcloud.gzippedListing = gzipped.toByteArray()
        val failure = failedSync()
        assertEquals(SourceException.Kind.LIMIT, failure.kind)
        assertTrue(failure.message!!.contains("64 MiB"), failure.message)
        assertPhotosStay()
        // A gzip listing within the limit is read.
        nextcloud.gzippedListing = null
        nextcloud.gzipListings = true
        assertEquals(ScanResult(0, 0, 0, 0), sync())
    }

    @Test
    fun `A listing that trickles longer than five minutes fails the sync as unreachable`() {
        synced()
        val padding = " ".repeat(400 * 1024)
        nextcloud.listingOverride = { multistatus(response(own, true), padding) }
        // 8 KiB every 10 seconds of the test clock: three times the minimum speed, but 400 KiB take more than 8 minutes.
        nextcloud.pacing =
            FakeNextcloud.Pacing(8192, { method, _ -> method == "PROPFIND" }) {
                Thread.sleep(20)
                clock.advance(Duration.ofSeconds(10))
            }
        val failure = failedSync()
        assertEquals(SourceException.Kind.UNREACHABLE, failure.kind)
        assertTrue(failure.message!!.contains("longer than 5 minutes"), failure.message)
        assertPhotosStay()
    }

    /**
     * Spec "Bounded answers of a server" › Large but legitimate folder. The listing comes from the server over HTTP; the
     * 20,000 downloads are written locally, as 20,000 requests to the fake would only measure the fake.
     */
    @Test
    fun `A folder of 20,000 photos syncs completely`() {
        val png = Fixtures.file("image.png").readBytes()
        repeat(20_000) { nextcloud.files["Photos/p$it.png"] = FakeNextcloud.Remote(png, "e$it") }
        assertEquals(20_000, folder.list(true).size, "the listing is read completely")
        val listedHere =
            object : io.github.tycho991.openframe.core.sources.RemoteFolder by folder {
                override fun download(
                    file: io.github.tycho991.openframe.core.sources.RemoteFile,
                    target: File,
                    limits: io.github.tycho991.openframe.core.sources.DownloadLimits,
                ): String? {
                    target.writeBytes(png)
                    return null
                }
            }
        val large = RemoteSource("nextcloud", { configs.get("nextcloud") }, FakeKind { listedHere }, index, engine, originals, {}, log)
        val result = runBlocking { large.scan() }
        assertEquals(20_000, result.added)
        assertEquals(20_000, photos().size)
    }

    // --- Listings stay inside the folder

    private fun assertOutsideFails(vararg children: String) {
        synced()
        nextcloud.listingOverride = { dir -> if (dir == "Photos") multistatus(response(own, true), *children) else null }
        val failure = failedSync()
        assertEquals(SourceException.Kind.PROTOCOL, failure.kind)
        assertTrue(failure.message!!.contains("outside"), failure.message)
        assertPhotosStay()
    }

    @Test
    fun `An entry named dot dot fails the listing`() = assertOutsideFails(response("$own..", true))

    @Test
    fun `An entry named dot fails the listing`() = assertOutsideFails(response("$own."))

    @Test
    fun `An entry with an empty name fails the listing`() = assertOutsideFails(response("$own/"))

    @Test
    fun `An entry outside the folder fails the listing and nothing outside is read`() {
        assertOutsideFails(response("/remote.php/dav/files/anna/Private/secret.jpg"), response("/remote.php/dav/files/anna/Private/", true))
        assertTrue(nextcloud.requests.none { "Private" in it }, "${nextcloud.requests}")
    }

    @Test
    fun `More than 32 levels of subfolders fail the sync as a limit`() {
        synced()
        nextcloud.put("Photos/" + (1..33).joinToString("/") { "d$it" } + "/deep.jpg", jpg(), "e9")
        val failure = failedSync()
        assertEquals(SourceException.Kind.LIMIT, failure.kind)
        assertTrue(failure.message!!.contains("32 levels"), failure.message)
        assertPhotosStay()
        nextcloud.files.remove("Photos/" + (1..33).joinToString("/") { "d$it" } + "/deep.jpg")
        nextcloud.put("Photos/" + (1..32).joinToString("/") { "d$it" } + "/deep.jpg", jpg(), "e9")
        assertEquals(1, sync().added, "32 levels are read")
    }

    @Test
    fun `More than 5,000 folders fail the sync as a limit`() {
        synced()
        repeat(SourceLimits.FOLDERS) { nextcloud.folders += "Photos/f$it" }
        val failure = failedSync()
        assertEquals(SourceException.Kind.LIMIT, failure.kind)
        assertTrue(failure.message!!.contains("5000 subfolders"), failure.message)
        assertPhotosStay()
    }

    // --- Bounded downloads

    @Test
    fun `A new file that sends more than listed is skipped as too large and the sync goes on`() {
        nextcloud.put("Photos/a.jpg", jpg(), "e1")
        nextcloud.files["Photos/b.jpg"] =
            FakeNextcloud.Remote(jpg() + ByteArray(2 * 1024 * 1024), "e2", listedSize = 3062L, sendLength = false)
        assertEquals(ScanResult(1, 0, 0, 1), sync())
        val skipped =
            index.library.value.skipped
                .single()
        assertEquals("b.jpg" to SkipReason.TOO_LARGE, skipped.path to skipped.reason)
        assertTrue(File(dir, "originals").list().orEmpty().none { it.endsWith(".part") })
        // Not downloaded again while size and time stay the same.
        nextcloud.requests.clear()
        sync()
        assertTrue(nextcloud.requests.none { it.startsWith("GET") }, "${nextcloud.requests}")
    }

    @Test
    fun `A known photo whose evicted original sends more than listed keeps its hidden state, comment and section`() {
        nextcloud.put("Photos/a.jpg", jpg(), "e1")
        sync()
        val first = photos().getValue("a.jpg")
        index.edit { it.put(first.copy(hidden = true, comment = "Grandma", crop = Crop(0.2, 0.8, 2.0))) }
        originals.remove(first.id)
        nextcloud.files.getValue("Photos/a.jpg").apply {
            content = jpg() + ByteArray(2 * 1024 * 1024)
            listedSize = jpg().size.toLong()
            sendLength = false
        }
        sync()
        val after = photos().getValue("a.jpg")
        assertTrue(after.hidden)
        assertEquals("Grandma", after.comment)
        assertNotNull(after.crop)
        assertEquals(first.version, after.version)
        assertTrue(
            index.library.value.skipped
                .isEmpty(),
        )
    }

    @Test
    fun `Without a listed size the announced length applies, without either the fixed cap`() {
        unknownSizeBytes = 1000
        nextcloud.files["Photos/a.jpg"] = FakeNextcloud.Remote(jpg(), "e1", listedSize = 0)
        nextcloud.files["Photos/b.jpg"] = FakeNextcloud.Remote(jpg(), "e2", listedSize = 0, sendLength = false)
        assertEquals(ScanResult(1, 0, 0, 1), sync())
        assertTrue(photos().containsKey("a.jpg"), "Content-Length plus the tolerance, not the cap of 1000 bytes")
        assertEquals(
            "b.jpg" to SkipReason.TOO_LARGE,
            index.library.value.skipped
                .single()
                .let { it.path to it.reason },
        )
    }

    @Test
    fun `A file replaced after the listing is skipped in this sync and fetched by the next`() {
        val bigger = jpg() + ByteArray(2 * 1024 * 1024)
        nextcloud.put("Photos/a.jpg", jpg(), "e1")
        nextcloud.files["Photos/b.jpg"] = FakeNextcloud.Remote(bigger, "e2", listedSize = jpg().size.toLong())
        nextcloud.files["Photos/c.jpg"] = FakeNextcloud.Remote(jpg() + ByteArray(100 * 1024), "e3", listedSize = jpg().size.toLong())
        assertEquals(ScanResult(2, 0, 0, 0), sync(), "b is changed, not a failure; c is within the tolerance")
        assertEquals(setOf("a.jpg", "c.jpg"), photos().keys)
        assertTrue(
            index.library.value.skipped
                .isEmpty(),
        )
        nextcloud.files.getValue("Photos/b.jpg").listedSize = null
        assertEquals(ScanResult(1, 0, 0, 0), sync())
        assertTrue(photos().containsKey("b.jpg"))
    }

    @Test
    fun `A download that would go below the reserve fails with storage and skips nothing`() {
        nextcloud.files["Photos/a.jpg"] = FakeNextcloud.Remote(jpg() + ByteArray(10_000), "e1", listedSize = 0, sendLength = false)
        freeBytes = OriginalsStore.RESERVE_BYTES + 5_000
        val failure = failedSync()
        assertEquals(SourceException.Kind.STORAGE, failure.kind)
        assertTrue(
            index.library.value.skipped
                .isEmpty(),
        )
        assertTrue(File(dir, "originals").list().orEmpty().isEmpty())
    }

    // --- Undecodable formats are not downloaded

    @Test
    fun `HEIC on a device without HEIF ends after its first bytes and is not downloaded again`() {
        engine.canDecodeHeif = false
        val heic = Fixtures.file("photo.heic").readBytes() + ByteArray(1024 * 1024)
        nextcloud.put("Photos/a.heic", heic, "e1")
        downloadedBytes.set(0)
        assertEquals(ScanResult(0, 0, 0, 1), sync())
        assertEquals(32, folder.lastDownloadBytes, "the frame read the first 32 bytes of the body")
        assertTrue(downloadedBytes.get() <= 8192, "OkHttp took one buffer of ${downloadedBytes.get()} bytes, not the 1 MiB file")
        assertEquals(
            SkipReason.UNSUPPORTED,
            index.library.value.skipped
                .single()
                .reason,
        )
        nextcloud.requests.clear()
        sync()
        assertTrue(nextcloud.requests.none { it.startsWith("GET") }, "${nextcloud.requests}")
    }

    @Test
    fun `A JPEG named heic is downloaded and shown`() {
        engine.canDecodeHeif = false
        nextcloud.put("Photos/a.heic", jpg(), "e1")
        assertEquals(ScanResult(1, 0, 0, 0), sync())
        assertTrue(originals.has(photos().getValue("a.heic")))
    }

    // --- Refetched original matches the index (design decision 15)

    private var stale = 0

    private val renderSource by lazy {
        RemoteSource(
            "nextcloud",
            { configs.get("nextcloud") },
            FakeKind { folder },
            index,
            engine,
            originals,
            {},
            log,
            onStale = { stale++ },
        )
    }

    private fun evicted(): PhotoEntry {
        nextcloud.put("Photos/a.jpg", jpg(), "e1")
        sync()
        val entry = photos().getValue("a.jpg")
        originals.remove(entry.id)
        return entry
    }

    @Test
    fun `A replaced file with other dimensions fails the rendering and makes the source sync`() {
        val entry = evicted()
        nextcloud.files.getValue("Photos/a.jpg").apply {
            content = Fixtures.file("rotated6.jpg").readBytes()
            etagHeader = "\"e2\""
        }
        assertEquals(SourceException.Kind.CHANGED, assertThrows(SourceException::class.java) { renderSource.original(entry) }.kind)
        assertEquals(1, stale)
        assertFalse(originals.has(entry))
    }

    @Test
    fun `A file announced 2 MiB larger than listed fails the rendering and makes the source sync`() {
        val entry = evicted()
        nextcloud.files.getValue("Photos/a.jpg").content = jpg() + ByteArray(2 * 1024 * 1024)
        assertEquals(SourceException.Kind.CHANGED, assertThrows(SourceException::class.java) { renderSource.original(entry) }.kind)
        assertEquals(1, stale)
    }

    @Test
    fun `A proxy that rewrites the entity tag of an unchanged file does not stop the rendering`() {
        val entry = evicted()
        nextcloud.files.getValue("Photos/a.jpg").etagHeader = "W/\"rewritten-by-proxy\""
        assertTrue(renderSource.original(entry).isFile)
        assertEquals(0, stale)
        assertEquals(2, engine.probes.get(), "one probe by the sync, one for the other tag")
    }

    @Test
    fun `The same entity tag is taken without a probe`() {
        val entry = evicted()
        nextcloud.files.getValue("Photos/a.jpg").etagHeader = "\"e1-gzip\""
        val probes = engine.probes.get()
        assertTrue(renderSource.original(entry).isFile)
        assertEquals(probes, engine.probes.get())
    }

    @Test
    fun `A sync that fetches an evicted original with other dimensions raises the version`() {
        val entry = evicted()
        nextcloud.files.getValue("Photos/a.jpg").content = Fixtures.file("rotated6.jpg").readBytes()
        assertEquals(ScanResult(0, 1, 0, 0), sync())
        val after = photos().getValue("a.jpg")
        assertEquals(entry.version + 1, after.version)
        assertTrue(after.width != entry.width || after.height != entry.height)
    }

    @Test
    fun `A download of a removed source ends and leaves no partial file`() {
        val entry = evicted()
        nextcloud.pacing =
            FakeNextcloud.Pacing(256, { method, _ -> method == "GET" }) {
                Thread.sleep(5)
            }
        nextcloud.files.getValue("Photos/a.jpg").content = jpg() + ByteArray(200_000)
        nextcloud.files.getValue("Photos/a.jpg").listedSize = jpg().size.toLong()
        val removed =
            Thread {
                Thread.sleep(100)
                configs.remove("nextcloud")
            }.apply { start() }
        val failure = assertThrows(SourceException::class.java) { renderSource.original(entry) }
        removed.join()
        assertEquals(SourceException.Kind.UNREACHABLE, failure.kind)
        assertTrue(failure.message!!.contains("removed"), failure.message)
        assertTrue(File(dir, "originals").list().orEmpty().none { it.endsWith(".part") })
    }

    @Test
    fun `Entity tags are compared without the weak marker, quotes and the suffix of a compressing proxy`() {
        assertNull(RemoteSource.normalizedEtag(""))
        assertNull(RemoteSource.normalizedEtag(null))
        assertEquals("e1", RemoteSource.normalizedEtag("W/\"e1-br\""))
        assertEquals("e1", RemoteSource.normalizedEtag("\"e1-gzip\""))
    }
}
