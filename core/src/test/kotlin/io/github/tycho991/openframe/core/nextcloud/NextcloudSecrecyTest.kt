package io.github.tycho991.openframe.core.nextcloud

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
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
    private val scope = TestScope()
    private val log = AppLog(TestClock())
    private val wrongPassword = "wrong-app-pw-Xk29"

    @BeforeEach
    fun start() = nextcloud.start()

    @AfterEach
    fun stop() {
        nextcloud.stop()
        scope.cancel()
    }

    private fun everythingTheFrameSays(): String {
        val view = configs.sources.value.toString()
        val entries = log.recent(1000).joinToString("\n") { it.message }
        return view + "\n" + entries
    }

    private val configs by lazy {
        SourceConfigStore(JsonStore(File(dir, "sources.json"), StoredSources.serializer(), TestScope(), log)).also { it.load() }
    }

    private val registry by lazy {
        val clients = OkHttpNextcloudClients { NextcloudHttp(NextcloudHttp.defaultClient(readTimeoutSeconds = 5)) }
        val index = PhotoIndexStore(JsonStore(File(dir, "index.json"), StoredIndex.serializer(), TestScope(), log)).also { it.load() }
        SourceRegistry(
            scope,
            configs,
            SyncResultStore(JsonStore(File(dir, "status.json"), StoredSyncResults.serializer(), TestScope(), log)),
            listOf(NextcloudAccountKind(clients), NextcloudShareKind(clients)),
            index,
            JvmMediaEngine(),
            File(dir, "sources"),
            {},
            TestClock(),
            log,
        ).also {
            it.start()
            val account = Connection.Account(nextcloud.baseUrl, nextcloud.userId, nextcloud.loginName, wrongPassword)
            it.add(StoredSource("nextcloud", NextcloudAccountKind.ID, "cloud", account, folder = "/"))
        }
    }

    @Test
    fun `A sync that the server refuses says nothing of the password`() {
        val failure = assertThrows(SourceException::class.java) { runBlocking { registry.source("nextcloud")!!.scan() } }
        assertEquals(SourceException.Kind.UNAUTHORIZED, failure.kind)
        val expectedHeader = "Basic " + Base64.getEncoder().encodeToString("${nextcloud.loginName}:$wrongPassword".toByteArray())
        for (text in listOf(failure.message.orEmpty(), failure.stackTraceToString(), everythingTheFrameSays())) {
            assertFalse(text.contains(wrongPassword), "password in: $text")
            assertFalse(text.contains(expectedHeader), "authorization header in: $text")
            assertFalse(text.contains(Base64.getEncoder().encodeToString("${nextcloud.loginName}:$wrongPassword".toByteArray())))
        }
    }

    @Test
    fun `A failing revoke on removal is logged without the password`() {
        nextcloud.faults["/ocs/v2.php/core/apppassword"] = 500
        registry.remove("nextcloud")
        assertFalse(everythingTheFrameSays().contains(wrongPassword))
        assertNull(configs.get("nextcloud"))
    }
}
