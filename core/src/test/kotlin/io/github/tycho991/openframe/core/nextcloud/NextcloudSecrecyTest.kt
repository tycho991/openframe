package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.library.PhotoIndexStore
import io.github.tycho991.openframe.core.library.StoredIndex
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Base64

/** Spec `nextcloud-source`: Secrecy of the credentials, with the real client against a server that refuses the password. */
class NextcloudSecrecyTest {
    @TempDir
    lateinit var dir: File

    private val nextcloud = FakeNextcloud()
    private val log = AppLog(TestClock())
    private val wrongPassword = "wrong-app-pw-Xk29"

    @BeforeEach
    fun start() = nextcloud.start()

    @AfterEach
    fun stop() = nextcloud.stop()

    private fun everythingTheFrameSays(): String {
        val view = configs.view().toString()
        val entries = log.recent(1000).joinToString("\n") { it.message }
        return view + "\n" + entries
    }

    private val configs by lazy {
        NextcloudConfigStore(JsonStore(File(dir, "nextcloud.json"), StoredNextcloud.serializer(), TestScope(), log)).also {
            it.load()
            it.connect(nextcloud.baseUrl, nextcloud.userId, nextcloud.loginName, wrongPassword)
        }
    }

    private val source by lazy {
        val clients = OkHttpNextcloudClients { NextcloudHttp(NextcloudHttp.defaultClient(readTimeoutSeconds = 5)) }
        val index = PhotoIndexStore(JsonStore(File(dir, "index.json"), StoredIndex.serializer(), TestScope(), log)).also { it.load() }
        NextcloudSource(configs, clients, index, JvmMediaEngine(), OriginalsStore(File(dir, "originals")), {}, log)
    }

    @Test
    fun `A sync that the server refuses says nothing of the password`() {
        val failure = assertThrows(NextcloudException::class.java) { runBlocking { source.scan() } }
        assertEquals(NextcloudException.Kind.UNAUTHORIZED, failure.kind)
        val expectedHeader = "Basic " + Base64.getEncoder().encodeToString("${nextcloud.loginName}:$wrongPassword".toByteArray())
        for (text in listOf(failure.message.orEmpty(), failure.stackTraceToString(), everythingTheFrameSays())) {
            assertFalse(text.contains(wrongPassword), "password in: $text")
            assertFalse(text.contains(expectedHeader), "authorization header in: $text")
            assertFalse(text.contains(Base64.getEncoder().encodeToString("${nextcloud.loginName}:$wrongPassword".toByteArray())))
        }
    }

    @Test
    fun `A failing revoke on disconnect is logged without the password`() {
        nextcloud.faults["/ocs/v2.php/core/apppassword"] = 500
        source.disconnect()
        assertFalse(everythingTheFrameSays().contains(wrongPassword))
        assertFalse(configs.view().connected)
    }
}
