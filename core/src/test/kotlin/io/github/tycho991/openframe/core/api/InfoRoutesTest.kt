package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.http.HttpPipeline
import io.github.tycho991.openframe.core.http.Router
import io.github.tycho991.openframe.core.identity.FrameIdentity
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.settings.SettingsRepository
import io.github.tycho991.openframe.core.settings.StoredSettings
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.FakeAuth
import io.github.tycho991.openframe.core.testing.FakePlatform
import io.github.tycho991.openframe.core.testing.TestClock
import io.github.tycho991.openframe.core.testing.TestHttp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `frame-settings`: Frame identity. */
class InfoRoutesTest {
    @TempDir
    lateinit var dir: File

    private val log = AppLog(TestClock())
    private val auth = FakeAuth()

    private fun identity() =
        runBlocking {
            FrameIdentity.loadOrCreate(JsonStore(File(dir, "identity.json"), FrameIdentity.serializer(), TestScope(), log))
        }

    private fun settings() =
        SettingsRepository(
            JsonStore(File(dir, "settings.json"), StoredSettings.serializer(), TestScope(), log),
            "PFF_1024",
            log,
        ).also { it.load() }

    private fun server(
        identity: FrameIdentity,
        settings: SettingsRepository,
    ) = TestHttp(
        HttpPipeline(
            Router().apply {
                infoRoutes(identity, settings, FakePlatform())
                settingsRoutes(settings)
            },
            auth,
            log,
        )::handle,
        checkContract = true,
    )

    @Test
    fun `Fetch info`() {
        val id = identity()
        server(id, settings()).use { http ->
            val res = http.get("/api/v1/info")
            assertEquals(200, res.statusCode())
            val info = ApiJson.decodeFromString(InfoDto.serializer(), res.body())
            assertEquals(InfoDto(id.id, "PFF_1024", "0.1.0-test", "6.0.1", SizeDto(1024, 600)), info)
            assertFalse(res.body().contains("intervalSeconds"))
        }
    }

    @Test
    fun `Change name`() {
        val id = identity()
        val settings = settings()
        server(id, settings).use { http ->
            assertEquals(200, http.request("PATCH", "/api/v1/settings", """{"name":"Living room"}""", auth.header).statusCode())
            val info = ApiJson.decodeFromString(InfoDto.serializer(), http.get("/api/v1/info").body())
            assertEquals("Living room", info.name)
            assertEquals(id.id, info.id)
        }
    }

    @Test
    fun `ID stays the same across restart`() {
        val first = identity()
        assertEquals(32, first.id.length)
        assertEquals(first, identity())
        assertEquals(first, identity())
    }
}
