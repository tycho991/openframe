package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.sources.SourceException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Design decision 17 of `nextcloud-hardening`: the standalone fake serves what the device rows need. */
class FakeNextcloudForDevicesTest {
    private val server = FakeNextcloud.forDevices("127.0.0.1", 0).also { it.start() }
    private val http = NextcloudHttp(NextcloudHttp.defaultClient(readTimeoutSeconds = 30))

    @AfterEach
    fun stop() = server.stop()

    private fun share(token: String) = ShareFolder(http, server.baseUrl, token, "", "/")

    @Test
    fun `The shares and the account of the device rows answer as intended`() {
        val doctype = assertThrows(SourceException::class.java) { share(FakeNextcloud.DOCTYPE_SHARE).list(true) }
        assertEquals(PropfindParser.DOCTYPE_REFUSED, doctype.message)
        assertEquals(FakeNextcloud.MANY_ENTRIES, share(FakeNextcloud.MANY_SHARE).list(true).size)
        assertEquals(20, share(FakeNextcloud.HEIC_SHARE).list(true).size)

        // The login page approves when it is opened, as the user would there.
        val flow = OkHttpLoginFlow(http)
        val start = flow.start(server.baseUrl)
        assertEquals(LoginPoll.Pending, flow.poll(start))
        http
            .execute(
                okhttp3.Request
                    .Builder()
                    .url(start.loginUrl)
                    .build(),
            ).close()
        val done = flow.poll(start) as LoginPoll.Done
        assertEquals(server.baseUrl, done.serverUrl)
        val photos =
            WebDavFolder(
                http,
                done.serverUrl,
                flow.userId(done.serverUrl, done.loginName, done.appPassword),
                done.loginName,
                done.appPassword,
                "/Photos",
            )
        assertEquals(listOf("a.jpg", "holiday/b.jpg"), photos.list(true).map { it.path }.sorted())
    }
}
