package io.github.tycho991.openframe.core.nextcloud

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

/** Spec `nextcloud-source`: Connect with the login flow, Sync of a folder (the client side), Secrecy of the credentials. */
class NextcloudClientTest {
    @TempDir
    lateinit var dir: File

    private val nextcloud = FakeNextcloud()
    private lateinit var http: NextcloudHttp

    @BeforeEach
    fun start() {
        nextcloud.start()
        // Short read timeout so that the stalled download takes a second, not a minute.
        http = NextcloudHttp(NextcloudHttp.defaultClient(readTimeoutSeconds = 1))
    }

    @AfterEach
    fun stop() = nextcloud.stop()

    private fun folder(
        path: String = "/",
        password: String = nextcloud.appPassword,
    ) = WebDavFolder(http, nextcloud.baseUrl, nextcloud.userId, nextcloud.loginName, password, path)

    private fun kindOf(block: () -> Unit) = assertThrows(NextcloudException::class.java, block).kind

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
        assertEquals(NextcloudException.Kind.SERVER, kindOf { OkHttpLoginFlow(http).start(nextcloud.baseUrl) })
    }

    @Test
    fun `A server that is not there`() {
        val closed = FakeNextcloud().also { it.start() }
        val url = closed.baseUrl
        closed.stop()
        assertEquals(NextcloudException.Kind.UNREACHABLE, kindOf { OkHttpLoginFlow(http).start(url) })
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
        assertEquals(NextcloudException.Kind.NOT_FOUND, kindOf { folder("/Nowhere").list(true) })
    }

    @Test
    fun `Wrong credentials are refused without naming them`() {
        val failure = assertThrows(NextcloudException::class.java) { folder("/", password = "wrong-pw-1234").list(true) }
        assertEquals(NextcloudException.Kind.UNAUTHORIZED, failure.kind)
        assertFalse(failure.message.orEmpty().contains("wrong-pw-1234"))
        assertFalse(failure.message.orEmpty().contains(nextcloud.appPassword))
    }

    @Test
    fun `An error in a subfolder fails the whole listing`() {
        nextcloud.put("Photos/a.jpg")
        nextcloud.put("Photos/holiday/b.jpg")
        nextcloud.faults["/remote.php/dav/files/anna/Photos/holiday"] = 503
        assertEquals(NextcloudException.Kind.SERVER, kindOf { folder("/Photos").list(true) })
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
        assertEquals(NextcloudException.Kind.PROTOCOL, kindOf { http.execute(request).close() })
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
        assertEquals(NextcloudException.Kind.NOT_FOUND, kindOf { folder("/Photos").download(RemoteFile("nope.jpg", "e", 1, 0), target) })
    }

    @Test
    fun `A download that stalls fails and leaves no file`() {
        nextcloud.put("Photos/slow.jpg", ByteArray(1000))
        nextcloud.downloadDelayMs = 3_000
        val target = File(dir, "slow.part")
        assertEquals(
            NextcloudException.Kind.UNREACHABLE,
            kindOf { folder("/Photos").download(RemoteFile("slow.jpg", "e", 1000, 0), target) },
        )
        assertFalse(target.exists())
    }
}
