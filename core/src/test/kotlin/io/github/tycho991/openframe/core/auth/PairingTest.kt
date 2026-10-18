package io.github.tycho991.openframe.core.auth

import io.github.tycho991.openframe.core.api.PairingResponseDto
import io.github.tycho991.openframe.core.api.pairingRoutes
import io.github.tycho991.openframe.core.api.settingsRoutes
import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.http.HttpPipeline
import io.github.tycho991.openframe.core.http.Router
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.settings.SettingsRepository
import io.github.tycho991.openframe.core.settings.StoredSettings
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.TestClock
import io.github.tycho991.openframe.core.testing.TestHttp
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration

/** Spec `admin-access`: Kopplung per Code, Schutz vor Durchprobieren, Token verwalten. */
class PairingTest {
    @TempDir
    lateinit var dir: File

    private val clock = TestClock()
    private val log = AppLog(clock)
    private val clients by lazy {
        ClientRegistry(
            JsonStore(File(dir, "clients.json"), StoredClients.serializer(), TestScope(), log),
            clock,
        )
    }
    private val signer by lazy {
        ImageSigner(JsonStore(File(dir, "secret.json"), StoredSecret.serializer(), TestScope(), log), clock).also { it.load() }
    }
    private val pairing by lazy { PairingManager(clients, clock, log) }
    private val http by lazy {
        val router =
            Router().apply {
                pairingRoutes(pairing, clients)
                settingsRoutes(
                    SettingsRepository(
                        JsonStore(File(dir, "s.json"), StoredSettings.serializer(), TestScope(), log),
                        "PFF_1024",
                        log,
                    ).also { it.load() },
                )
            }
        TestHttp(HttpPipeline(router, AccessControl(clients, signer), log)::handle, checkContract = true)
    }

    @AfterEach
    fun tearDown() = http.close()

    private fun requestCode(): String {
        assertEquals(204, http.request("POST", "/api/v1/pairing/code").statusCode())
        return pairing.display.value.code!!
    }

    private fun pair(
        code: String,
        name: String? = null,
        ua: String? = null,
    ) = http.request(
        "POST",
        "/api/v1/pairing",
        if (name == null) """{"code":"$code"}""" else """{"code":"$code","deviceName":"$name"}""",
        headers = if (ua != null) mapOf("User-Agent" to ua) else emptyMap(),
    )

    private fun errorCode(body: String) =
        Json
            .parseToJsonElement(body)
            .jsonObject["error"]!!
            .jsonObject["code"]!!
            .jsonPrimitive.content

    @Test
    fun `Erfolgreiche Kopplung`() {
        val code = requestCode()
        assertTrue(code.matches(Regex("[0-9]{6}")))
        val res = pair(code, "Laptop")
        assertEquals(201, res.statusCode())
        val body = ApiJson.decodeFromString(PairingResponseDto.serializer(), res.body())
        assertEquals("Laptop", body.client.name)
        assertTrue(body.client.current)
        assertEquals(200, http.get("/api/v1/clients", mapOf("Authorization" to "Bearer ${body.token}")).statusCode())
        // Gespeichert wird nur der Hash.
        assertFalse(File(dir, "clients.json").readText().contains(body.token))
        assertTrue(File(dir, "clients.json").readText().contains(ClientRegistry.hash(body.token)))
        // Nach erfolgreicher Kopplung verschwindet der Code vom Rahmen.
        assertNull(pairing.display.value.code)
    }

    @Test
    fun `Code nur einmal verwendbar`() {
        val code = requestCode()
        assertEquals(201, pair(code).statusCode())
        val again = pair(code)
        assertEquals(401, again.statusCode())
    }

    @Test
    fun `Code abgelaufen`() {
        val code = requestCode()
        clock.advance(Duration.ofMinutes(5).plusSeconds(1))
        val res = pair(code)
        assertEquals(401, res.statusCode())
        assertEquals("expired_code", errorCode(res.body()))
        // Auf Anfrage zeigt der Rahmen einen neuen Code.
        val fresh = requestCode()
        assertEquals(201, pair(fresh).statusCode())
    }

    @Test
    fun `Code am Rahmen anfordern`() {
        val res = http.request("POST", "/api/v1/pairing/code")
        assertEquals(204, res.statusCode())
        assertEquals("", res.body())
        val shown = pairing.display.value
        assertNotNull(shown.code)
        assertEquals(clock.now() + Duration.ofMinutes(5), shown.requestedUntil)
        // Ein zweiter Aufruf erzeugt keinen neuen Code, solange der alte gilt.
        http.request("POST", "/api/v1/pairing/code")
        assertEquals(shown.code, pairing.display.value.code)
        // Nach 5 Minuten endet das Anfragefenster.
        clock.advance(Duration.ofMinutes(5))
        pairing.tick()
        assertNull(pairing.display.value.code)
        assertNull(pairing.display.value.requestedUntil)
    }

    @Test
    fun `Angezeigter Code wird nach Ablauf ersetzt`() {
        pairing.setShownOnFrame(true)
        val first = pairing.display.value.code!!
        clock.advance(Duration.ofMinutes(4))
        pairing.tick()
        assertEquals(first, pairing.display.value.code)
        clock.advance(Duration.ofMinutes(1))
        pairing.tick()
        val second = pairing.display.value.code!!
        assertNotEquals(first, second)
        assertEquals("expired_code", errorCode(pair(first).body()))
        assertEquals(201, pair(second).statusCode())
    }

    @Test
    fun `Zu viele Fehlversuche`() {
        val code = requestCode()
        val wrong = if (code == "000000") "111111" else "000000"
        repeat(4) { assertEquals("invalid_code", errorCode(pair(wrong).body())) }
        assertEquals(401, pair(wrong).statusCode())
        // Gesperrt, auch für den richtigen Code und für das Anfordern eines neuen.
        val locked = pair(code)
        assertEquals(429, locked.statusCode())
        assertEquals("60", locked.headers().firstValue("retry-after").get())
        assertEquals(429, http.request("POST", "/api/v1/pairing/code").statusCode())
        assertNull(pairing.display.value.code)
        clock.advance(Duration.ofSeconds(59))
        assertEquals(429, pair(code).statusCode())
        clock.advance(Duration.ofSeconds(1))
        pairing.tick()
        // Danach gilt ein neuer Code, der alte ist verworfen.
        assertEquals(401, pair(code).statusCode())
        val fresh = requestCode()
        assertEquals(201, pair(fresh).statusCode())
    }

    @Test
    fun `Geraetename aus dem User-Agent`() {
        val ua = "Mozilla/5.0 (Android 14; Mobile; rv:131.0) Gecko/131.0 Firefox/131.0"
        val res = pair(requestCode(), ua = ua)
        assertEquals("Firefox auf Android", ApiJson.decodeFromString(PairingResponseDto.serializer(), res.body()).client.name)
        assertEquals(
            "Safari auf iPhone",
            DeviceName.fromUserAgent(
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
            ),
        )
        assertEquals(
            "Chrome auf Windows",
            DeviceName.fromUserAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36",
            ),
        )
        assertEquals("Unbekanntes Gerät", DeviceName.fromUserAgent(null))
    }

    @Test
    fun `Ungueltige Eingaben`() {
        assertEquals(400, pair("12345").statusCode())
        assertEquals(400, http.request("POST", "/api/v1/pairing", """{"kode":"123456"}""").statusCode())
    }

    @Test
    fun `Ohne Token, mit Token, Kopplung aufheben`() {
        assertEquals(401, http.get("/api/v1/settings").statusCode())
        val laptop = ApiJson.decodeFromString(PairingResponseDto.serializer(), pair(requestCode(), "Alter Laptop").body())
        val phone = ApiJson.decodeFromString(PairingResponseDto.serializer(), pair(requestCode(), "Handy").body())
        val auth = mapOf("Authorization" to "Bearer ${phone.token}")
        assertEquals(200, http.get("/api/v1/settings", auth).statusCode())
        assertEquals(401, http.get("/api/v1/settings", mapOf("Authorization" to "Bearer falsch")).statusCode())

        val list = Json.parseToJsonElement(http.get("/api/v1/clients", auth).body()).jsonObject["clients"]!!.jsonArray
        assertEquals(
            listOf("Alter Laptop" to false, "Handy" to true),
            list.map {
                it.jsonObject["name"]!!.jsonPrimitive.content to
                    (it.jsonObject["current"]!!.jsonPrimitive.content == "true")
            },
        )

        assertEquals(204, http.request("DELETE", "/api/v1/clients/${laptop.client.id}", headers = auth).statusCode())
        assertEquals(401, http.get("/api/v1/settings", mapOf("Authorization" to "Bearer ${laptop.token}")).statusCode())
        assertEquals(200, http.get("/api/v1/settings", auth).statusCode())
        assertEquals(404, http.request("DELETE", "/api/v1/clients/${laptop.client.id}", headers = auth).statusCode())

        // Überlebt einen Neustart.
        val reloaded =
            ClientRegistry(
                JsonStore(File(dir, "clients.json"), StoredClients.serializer(), TestScope(), log),
                clock,
            ).also { it.load() }
        assertEquals(listOf("Handy"), reloaded.clients.value.map { it.name })
        assertNotNull(reloaded.authenticate(phone.token))
    }
}
