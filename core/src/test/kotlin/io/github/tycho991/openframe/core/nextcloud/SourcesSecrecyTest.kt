package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.api.SourceDto
import io.github.tycho991.openframe.core.api.SourceListDto
import io.github.tycho991.openframe.core.api.SourceLoginDto
import io.github.tycho991.openframe.core.api.SourceLoginStateDto
import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.TestFrame
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Base64

/**
 * Spec `nextcloud-source`: Secrecy of the credentials. An account and a protected share with secrets that are easy to
 * find, failed syncs of both and refused requests; then every answer of the API, the log and the log files are searched
 * for the secrets and for the authorization headers made from them.
 */
class SourcesSecrecyTest {
    @TempDir
    lateinit var dir: File

    private val appPassword = "AppSecret-Qq19"
    private val token = "ShareTok3n77"
    private val sharePassword = "SharePw-Zz42"
    private val nextcloud = FakeNextcloud(appPassword = appPassword)
    private lateinit var frame: TestFrame
    private val answers = ArrayList<String>()

    @BeforeEach
    fun start() {
        nextcloud.start()
        frame =
            TestFrame(dir, nextcloudClients = OkHttpNextcloudClients { NextcloudHttp(NextcloudHttp.defaultClient(readTimeoutSeconds = 5)) })
    }

    @AfterEach
    fun stop() {
        frame.close()
        nextcloud.stop()
    }

    private fun send(
        method: String,
        path: String,
        body: String? = null,
    ) = frame.send(method, path, body).also { answers += it.body() }

    private fun sources() = ApiJson.decodeFromString(SourceListDto.serializer(), send("GET", "/api/v1/sources").body()).sources

    private fun waitForFailure(id: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            val status = ApiJson.decodeFromString(SourceDto.serializer(), send("GET", "/api/v1/sources/$id").body()).status
            if (!status.syncing && status.ok == false) return
            check(System.currentTimeMillis() < deadline) { "the sync of $id did not fail" }
            Thread.sleep(25)
        }
    }

    @Test
    fun `No answer, log line or error text carries a password, a share token or an authorization header`() {
        nextcloud.put("Photos/a.jpg", Fixtures.file("landscape.jpg").readBytes(), "e1")
        nextcloud.put("Shared/b.jpg", Fixtures.file("landscape.jpg").readBytes(), "e2")
        nextcloud.shares[token] = FakeNextcloud.Share("Shared", password = sharePassword)

        // An account through the login flow.
        val flow =
            ApiJson.decodeFromString(
                SourceLoginDto.serializer(),
                send("POST", "/api/v1/sources/login", """{"serverUrl":"${nextcloud.baseUrl}"}""").body(),
            )
        nextcloud.approved = true
        val account =
            ApiJson
                .decodeFromString(
                    SourceLoginStateDto.serializer(),
                    send("GET", "/api/v1/sources/login/${flow.flowId}").body(),
                ).sourceId!!
        send("PATCH", "/api/v1/sources/$account", """{"folder":"/Photos"}""")

        // Refused shares, then the right password.
        val link = "${nextcloud.baseUrl}/s/$token"
        assertEquals(400, send("POST", "/api/v1/sources", """{"kind":"nextcloud-share","link":"$link"}""").statusCode())
        assertEquals(
            400,
            send("POST", "/api/v1/sources", """{"kind":"nextcloud-share","link":"$link","password":"wrong-$sharePassword"}""").statusCode(),
        )
        val share = send("POST", "/api/v1/sources", """{"kind":"nextcloud-share","link":"$link","password":"$sharePassword"}""")
        assertEquals(201, share.statusCode())
        val shareId = ApiJson.decodeFromString(SourceDto.serializer(), share.body()).id
        assertEquals(
            409,
            send("POST", "/api/v1/sources", """{"kind":"nextcloud-share","link":"$link","password":"$sharePassword"}""").statusCode(),
        )

        // Both fail: the app password is revoked on the server, the owner changed the password of the share.
        nextcloud.acceptedAppPassword = "revoked"
        nextcloud.shares.getValue(token).password = "changed"
        send("POST", "/api/v1/sources/$account/sync")
        waitForFailure(account)
        send("POST", "/api/v1/sources/$shareId/sync")
        waitForFailure(shareId)
        sources()
        send("GET", "/api/v1/logs?limit=5000")

        val logFiles =
            File(frame.dirs.state, "logs")
                .walkTopDown()
                .filter { it.isFile }
                .joinToString("\n") { it.readText() }
        val texts =
            answers +
                frame.core.log
                    .recent(5000)
                    .map { it.message } + logFiles + sources().map { it.toString() } +
                frame.core.sources.configs.sources.value
                    .map { it.toString() }
        val secrets =
            listOf(
                appPassword,
                token,
                sharePassword,
                Base64.getEncoder().encodeToString("${nextcloud.loginName}:$appPassword".toByteArray()),
                Base64.getEncoder().encodeToString("$token:$sharePassword".toByteArray()),
                Base64.getEncoder().encodeToString("$token:".toByteArray()).trimEnd('='),
            )
        for (text in texts) {
            for (secret in secrets) assertFalse(text.contains(secret), "\"$secret\" in: $text")
        }
        // The failures are there, readable.
        val errors = sources().associate { it.id to it.status.error }
        assertEquals("The server refused the credentials (HTTP 401)", errors[account])
        assertEquals("The share refused the password (HTTP 401)", errors[shareId])
    }

    /** Spec `nextcloud-source`: Secrecy of the credentials › Unreadable list of sources. */
    @Test
    fun `A damaged sources json leaves no trace of its secrets in the log`() {
        val other = File(dir, "other")
        File(other, "state").mkdirs()
        // Truncated in the middle of a share's password, and once with a type error right after the secret.
        val damaged =
            listOf(
                """{"sources":[{"id":"s1","kind":"nextcloud-share","link":"https://cloud.example.com/s/$token","password":"$sharePassword""",
                """{"sources":[{"id":"s1","kind":"nextcloud-share","appPassword":"$appPassword","enabled":"$sharePassword"}]}""",
            )
        for (text in damaged) {
            File(other, "state/sources.json").writeText(text)
            TestFrame(other).use { broken ->
                val logged = broken.core.log.recent(5000)
                assertTrue(logged.any { "sources.json not readable" in it.message }, "the entry is there")
                val logFiles =
                    File(broken.dirs.state, "logs")
                        .walkTopDown()
                        .filter { it.isFile }
                        .joinToString("\n") { it.readText() }
                for (secret in listOf(appPassword, token, sharePassword)) {
                    assertFalse(logged.any { secret in it.message }, "\"$secret\" in the log")
                    assertFalse(secret in logFiles, "\"$secret\" in the log file")
                }
            }
        }
    }
}
