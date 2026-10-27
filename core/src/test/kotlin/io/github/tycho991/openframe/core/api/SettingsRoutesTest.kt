package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.http.HttpPipeline
import io.github.tycho991.openframe.core.http.Router
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.settings.Settings
import io.github.tycho991.openframe.core.settings.SettingsRepository
import io.github.tycho991.openframe.core.settings.StoredSettings
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.FakeAuth
import io.github.tycho991.openframe.core.testing.TestClock
import io.github.tycho991.openframe.core.testing.TestHttp
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SettingsRoutesTest {
    @TempDir
    lateinit var dir: File

    private val log = AppLog(TestClock())
    private val auth = FakeAuth()
    private val repo by lazy {
        SettingsRepository(
            JsonStore(File(dir, "s.json"), StoredSettings.serializer(), TestScope(), log),
            "PFF_1024",
            log,
        ).also { it.load() }
    }
    private val http by lazy { TestHttp(HttpPipeline(Router().apply { settingsRoutes(repo) }, auth, log)::handle, checkContract = true) }

    @AfterEach
    fun tearDown() = http.close()

    @Test
    fun `Read settings`() {
        val res = http.get("/api/v1/settings", auth.header)
        assertEquals(200, res.statusCode())
        assertEquals(Settings.defaults("PFF_1024"), ApiJson.decodeFromString(Settings.serializer(), res.body()))
    }

    @Test
    fun `Change only the interval`() {
        val res = http.request("PATCH", "/api/v1/settings", """{"intervalSeconds":60}""", auth.header)
        assertEquals(200, res.statusCode())
        assertEquals(Settings.defaults("PFF_1024").copy(intervalSeconds = 60), ApiJson.decodeFromString(Settings.serializer(), res.body()))
        assertEquals(60, repo.settings.value.intervalSeconds)
        assertEquals(1, repo.version.value)
    }

    @Test
    fun `Errors per field`() {
        val res = http.request("PATCH", "/api/v1/settings", """{"intervalSeconds":1,"clock":{"enabled":false}}""", auth.header)
        assertEquals(400, res.statusCode())
        val fields =
            Json
                .parseToJsonElement(res.body())
                .jsonObject["error"]!!
                .jsonObject["fields"]!!
                .jsonObject
        assertEquals(setOf("intervalSeconds"), fields.keys)
        assertEquals(true, repo.settings.value.clock.enabled)
    }

    /** Spec `frame-settings`: Caption settings › Invalid corner, through the API. */
    @Test
    fun `Invalid caption corner names its field`() {
        val res = http.request("PATCH", "/api/v1/settings", """{"caption":{"position":"middle"}}""", auth.header)
        assertEquals(400, res.statusCode())
        val fields =
            Json
                .parseToJsonElement(res.body())
                .jsonObject["error"]!!
                .jsonObject["fields"]!!
                .jsonObject
        assertEquals(setOf("caption.position"), fields.keys)
        val ok = http.request("PATCH", "/api/v1/settings", """{"caption":{"position":"bottom-right","place":true}}""", auth.header)
        assertEquals(200, ok.statusCode())
        assertEquals(true, ApiJson.decodeFromString(Settings.serializer(), ok.body()).caption.place)
    }

    @Test
    fun `Via the API`() {
        val res = http.request("PATCH", "/api/v1/settings", """{"localOnly":false}""", auth.header)
        assertEquals(400, res.statusCode())
        repo.setLocalOnly(true)
        assertEquals(400, http.request("PATCH", "/api/v1/settings", """{"localOnly":false}""", auth.header).statusCode())
        assertEquals(true, repo.localOnly.value)
    }

    @Test
    fun `Without token`() {
        assertEquals(401, http.get("/api/v1/settings").statusCode())
        assertEquals(401, http.request("PATCH", "/api/v1/settings", """{"intervalSeconds":60}""").statusCode())
        assertEquals(30, repo.settings.value.intervalSeconds)
    }

    @Test
    fun `Not an object`() {
        assertEquals(400, http.request("PATCH", "/api/v1/settings", "[1]", auth.header).statusCode())
        assertFalse(repo.version.value > 0)
        val body = Json.parseToJsonElement(http.get("/api/v1/settings", auth.header).body()).jsonObject
        assertEquals(30, body["intervalSeconds"]!!.jsonPrimitive.int)
    }
}
