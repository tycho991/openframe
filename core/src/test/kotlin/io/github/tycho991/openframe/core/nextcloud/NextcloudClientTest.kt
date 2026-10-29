package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.sources.RemoteFile
import io.github.tycho991.openframe.core.sources.SourceException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Spec `nextcloud-source`: Connect with the login flow, Connect with a share link, Password of a share, Secrecy of the
 * credentials; `remote-sources`: Sync of a folder (the client side).
 */
class NextcloudClientTest {
    @TempDir
    lateinit var dir: File

    private val nextcloud = FakeNextcloud()
    private lateinit var http: NextcloudHttp

    @BeforeEach
    fun start() {
        nextcloud.start()
        // Generous on a busy machine, yet a request that hangs fails in seconds rather than after the frame's minute.
        http = NextcloudHttp(NextcloudHttp.defaultClient(readTimeoutSeconds = 10))
    }

    @AfterEach
    fun stop() = nextcloud.stop()

    private fun folder(
        path: String = "/",
        password: String = nextcloud.appPassword,
        http: NextcloudHttp = this.http,
    ) = WebDavFolder(http, nextcloud.baseUrl, nextcloud.userId, nextcloud.loginName, password, path)

    private fun kindOf(block: () -> Unit) = assertThrows(SourceException::class.java, block).kind

    // --- Login flow

    @Test
    fun `The login is pending until the user approves`() {
        val flow = OkHttpLoginFlow(http)
        val start = flow.start(nextcloud.baseUrl)
        assertEquals("${nextcloud.baseUrl}/index.php/login/v2/flow/abc", start.loginUrl)
        assertEquals(LoginPoll.Pending, flow.poll(start))
        nextcloud.approved = true
        val done = flow.poll(start) as LoginPoll.Done
        assertEquals(nextcloud.loginName, done.loginName)
        assertEquals(nextcloud.appPassword, done.appPassword)
        assertEquals(nextcloud.baseUrl, done.serverUrl)
    }

    @Test
    fun `The user ID is read from the account and the app password can be revoked`() {
        val flow = OkHttpLoginFlow(http)
        assertEquals("anna", flow.userId(nextcloud.baseUrl, nextcloud.loginName, nextcloud.appPassword))
        flow.revoke(nextcloud.baseUrl, nextcloud.loginName, nextcloud.appPassword)
        assertTrue(nextcloud.revoked)
    }

    @Test
    fun `A server that does not answer like Nextcloud`() {
        nextcloud.faults["/index.php/login/v2"] = 500
        assertEquals(SourceException.Kind.SERVER, kindOf { OkHttpLoginFlow(http).start(nextcloud.baseUrl) })
    }

    @Test
    fun `A server that is not there`() {
        val closed = FakeNextcloud().also { it.start() }
        val url = closed.baseUrl
        closed.stop()
        assertEquals(SourceException.Kind.UNREACHABLE, kindOf { OkHttpLoginFlow(http).start(url) })
    }

    // --- Listing

    @Test
    fun `A folder tree is listed with etags, sizes and dates`() {
        nextcloud.put("Photos/a.jpg", ByteArray(10), "e1")
        nextcloud.put("Photos/holiday/b.jpg", ByteArray(20), "e2")
        nextcloud.put("Photos/holiday/2025/c.png", ByteArray(30), "e3")
        nextcloud.put("Other/x.jpg")
        val files = folder("/Photos").list(recursive = true).sortedBy { it.path }
        assertEquals(listOf("a.jpg", "holiday/2025/c.png", "holiday/b.jpg"), files.map { it.path })
        assertEquals(listOf("e1", "e3", "e2"), files.map { it.etag })
        assertEquals(listOf(10L, 30L, 20L), files.map { it.size })
        assertTrue(files.all { it.modifiedMs == 1_760_000_000_000L })
    }

    @Test
    fun `Without subfolders only the top of the folder is listed`() {
        nextcloud.put("Photos/a.jpg")
        nextcloud.put("Photos/holiday/b.jpg")
        assertEquals(listOf("a.jpg"), folder("/Photos").list(recursive = false).map { it.path })
    }

    @Test
    fun `The top folder and names with spaces and umlauts`() {
        nextcloud.put("Urlaub 2025/Müller & Söhne.jpg")
        nextcloud.put("top.jpg")
        val files = folder("/").list(recursive = true).map { it.path }.sorted()
        assertEquals(listOf("Urlaub 2025/Müller & Söhne.jpg", "top.jpg"), files)
    }

    @Test
    fun `Names with characters that URI refuses are listed and downloaded`() {
        nextcloud.put("Fotos [2025]/a|b {c}.jpg", ByteArray(5), "e1")
        nextcloud.put("Fotos [2025]/Sub Ordner/100%.jpg", ByteArray(6), "e2")
        val files = folder("/Fotos [2025]").list(recursive = true).sortedBy { it.path }
        assertEquals(listOf("Sub Ordner/100%.jpg", "a|b {c}.jpg"), files.map { it.path })
        val target = File(dir, "x.part")
        folder("/Fotos [2025]").download(files.first { it.path.startsWith("a|b") }, target)
        assertEquals(5, target.length())
    }

    @Test
    fun `An empty folder`() {
        nextcloud.folders += "Photos"
        assertEquals(emptyList<RemoteFile>(), folder("/Photos").list(recursive = true))
    }

    @Test
    fun `A folder that does not exist`() {
        assertEquals(SourceException.Kind.NOT_FOUND, kindOf { folder("/Nowhere").list(true) })
    }

    @Test
    fun `Wrong credentials are refused without naming them`() {
        val failure = assertThrows(SourceException::class.java) { folder("/", password = "wrong-pw-1234").list(true) }
        assertEquals(SourceException.Kind.UNAUTHORIZED, failure.kind)
        assertFalse(failure.message.orEmpty().contains("wrong-pw-1234"))
        assertFalse(failure.message.orEmpty().contains(nextcloud.appPassword))
    }

    @Test
    fun `An error in a subfolder fails the whole listing`() {
        nextcloud.put("Photos/a.jpg")
        nextcloud.put("Photos/holiday/b.jpg")
        nextcloud.faults["/remote.php/dav/files/anna/Photos/holiday"] = 503
        assertEquals(SourceException.Kind.SERVER, kindOf { folder("/Photos").list(true) })
    }

    // --- Redirects

    @Test
    fun `A redirect within the same server is followed`() {
        nextcloud.put("a.jpg")
        nextcloud.redirects["/old.php/login"] = "${nextcloud.baseUrl}/index.php/login/v2"
        val request =
            okhttp3.Request
                .Builder()
                .url("${nextcloud.baseUrl}/old.php/login")
                .post(
                    ByteArray(0).let {
                        okhttp3.RequestBody.create(null, it)
                    },
                ).build()
        http.execute(request).use { assertEquals(200, it.code) }
    }

    @Test
    fun `A redirect to another host is refused`() {
        val other = nextcloud.baseUrl.replace("127.0.0.1", "localhost")
        nextcloud.redirects["/go"] = "$other/index.php/login/v2"
        val request =
            okhttp3.Request
                .Builder()
                .url("${nextcloud.baseUrl}/go")
                .get()
                .build()
        assertEquals(SourceException.Kind.PROTOCOL, kindOf { http.execute(request).close() })
    }

    // --- Download

    @Test
    fun `A file is downloaded as it is`() {
        val content = ByteArray(300_000) { (it % 251).toByte() }
        nextcloud.put("Photos/big.jpg", content, "e9")
        val target = File(dir, "big.part")
        folder("/Photos").download(RemoteFile("big.jpg", "e9", content.size.toLong(), 0), target)
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun `A file that is gone`() {
        val target = File(dir, "x.part")
        assertEquals(SourceException.Kind.NOT_FOUND, kindOf { folder("/Photos").download(RemoteFile("nope.jpg", "e", 1, 0), target) })
    }

    @Test
    fun `A download that stalls fails and leaves no file`() {
        nextcloud.put("Photos/slow.jpg", ByteArray(1000))
        nextcloud.downloadDelayMs = 3_000
        // A short read timeout of its own, so that the stalled download takes a second.
        val impatient = NextcloudHttp(NextcloudHttp.defaultClient(readTimeoutSeconds = 1))
        val target = File(dir, "slow.part")
        assertEquals(
            SourceException.Kind.UNREACHABLE,
            kindOf { folder("/Photos", http = impatient).download(RemoteFile("slow.jpg", "e", 1000, 0), target) },
        )
        assertFalse(target.exists())
    }

    // --- Shares

    private fun share(
        token: String = "AbC123",
        password: String = "",
        path: String = "/",
    ) = ShareFolder(http, nextcloud.baseUrl, token, password, path)

    @Test
    fun `A share lists its subfolders and sends the token and password as credentials`() {
        nextcloud.put("Shared/holiday/a.jpg", ByteArray(10), "e1")
        nextcloud.put("Shared/holiday/2025/b.png", ByteArray(20), "e2")
        nextcloud.put("Shared/top.jpg", ByteArray(30), "e3")
        nextcloud.put("Private/x.jpg")
        nextcloud.shares["AbC123"] = FakeNextcloud.Share("Shared", password = "pw-ä1")
        val files = share(password = "pw-ä1").list(recursive = true).sortedBy { it.path }
        assertEquals(listOf("holiday/2025/b.png", "holiday/a.jpg", "top.jpg"), files.map { it.path })
        assertEquals(listOf("e2", "e1", "e3"), files.map { it.etag })
        val expected =
            "Basic " +
                java.util.Base64
                    .getEncoder()
                    .encodeToString("AbC123:pw-ä1".toByteArray(Charsets.UTF_8))
        assertTrue(nextcloud.authorizations.isNotEmpty() && nextcloud.authorizations.all { it == expected })
        assertTrue(nextcloud.requests.all { "AbC123" !in it }, "the token is never in a URL: ${nextcloud.requests}")
        assertEquals(listOf("top.jpg"), share(password = "pw-ä1").list(recursive = false).map { it.path })
        assertEquals(listOf("a.jpg"), share(password = "pw-ä1", path = "/holiday").list(recursive = false).map { it.path })
    }

    @Test
    fun `A file of a share is downloaded by its listing`() {
        val content = ByteArray(5_000) { (it % 251).toByte() }
        nextcloud.put("Shared/a.jpg", content, "e1")
        nextcloud.shares["AbC123"] = FakeNextcloud.Share("Shared")
        val file = share().list(true).single()
        assertEquals("e1", file.etag)
        val target = File(dir, "a.part")
        share().download(file, target)
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun `Reading the top of a share tells a folder from a single file and names it`() {
        nextcloud.put("Shared/a.jpg")
        nextcloud.put("Single/photo.jpg")
        nextcloud.shares["AbC123"] = FakeNextcloud.Share("Shared")
        nextcloud.shares["File42"] = FakeNextcloud.Share("Single/photo.jpg")
        assertEquals(ShareInfo(ShareRoot.FOLDER, "Shared"), share().probe())
        assertEquals(ShareInfo(ShareRoot.FILE, "photo.jpg"), share(token = "File42").probe())
        assertTrue(nextcloud.requests.filter { it.startsWith("PROPFIND") }.size == 2, "one request per share")
    }

    @Test
    fun `A missing or wrong password is refused, an unknown or withdrawn share is not found`() {
        nextcloud.put("Shared/a.jpg")
        nextcloud.shares["AbC123"] = FakeNextcloud.Share("Shared", password = "right")
        assertEquals(SourceException.Kind.UNAUTHORIZED, kindOf { share().probe() })
        val wrong = assertThrows(SourceException::class.java) { share(password = "wrong-Kz71").list(true) }
        assertEquals(SourceException.Kind.UNAUTHORIZED, wrong.kind)
        assertFalse(wrong.message.orEmpty().contains("wrong-Kz71") || wrong.message.orEmpty().contains("AbC123"))
        // Nextcloud answers an unknown token with 401 as well; the page of the share tells it apart.
        val unknown = assertThrows(SourceException::class.java) { share(token = "Nope99").probe() }
        assertEquals(SourceException.Kind.GONE, unknown.kind)
        assertFalse(unknown.message.orEmpty().contains("Nope99"))
        // A server that answers 404 itself.
        nextcloud.faults["/public.php/webdav"] = 404
        assertEquals(SourceException.Kind.NOT_FOUND, kindOf { share(password = "right").probe() })
    }

    @Test
    fun `A download from a share that was withdrawn meanwhile is gone, not a vanished file`() {
        nextcloud.put("Shared/a.jpg", ByteArray(10), "e1")
        nextcloud.shares["AbC123"] = FakeNextcloud.Share("Shared")
        val file = share().list(true).single()
        nextcloud.shares.remove("AbC123")
        assertEquals(SourceException.Kind.GONE, kindOf { share().download(file, File(dir, "a.part")) })
    }

    @Test
    fun `A file drop that only accepts uploads is not readable`() {
        nextcloud.folders += "Drop"
        nextcloud.shares["Drop42"] = FakeNextcloud.Share("Drop", uploadOnly = true)
        val probe = assertThrows(SourceException::class.java) { share(token = "Drop42").probe() }
        assertEquals(SourceException.Kind.NOT_READABLE, probe.kind)
        assertFalse(probe.message.orEmpty().contains("Drop42"))
        assertEquals(SourceException.Kind.NOT_READABLE, kindOf { share(token = "Drop42").list(true) })
    }

    @Test
    fun `A server that is not there for a share`() {
        val closed = FakeNextcloud().also { it.start() }
        val url = closed.baseUrl
        closed.stop()
        assertEquals(SourceException.Kind.UNREACHABLE, kindOf { ShareFolder(http, url, "AbC123", "", "/").probe() })
    }
}
