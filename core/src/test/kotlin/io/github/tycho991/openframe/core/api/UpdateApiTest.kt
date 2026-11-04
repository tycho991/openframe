package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.platform.InstallResult
import io.github.tycho991.openframe.core.testing.Contract
import io.github.tycho991.openframe.core.testing.FakeAppInstaller
import io.github.tycho991.openframe.core.testing.FakeDisplayPower
import io.github.tycho991.openframe.core.testing.FakePlatform
import io.github.tycho991.openframe.core.testing.FakeReleaseSource
import io.github.tycho991.openframe.core.testing.ReadyUpdate
import io.github.tycho991.openframe.core.testing.TestClock
import io.github.tycho991.openframe.core.testing.TestFrame
import io.github.tycho991.openframe.core.testing.waitFor
import io.github.tycho991.openframe.core.update.UpdatePhase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

/**
 * Spec `app-update`: Update state in the API, Fixed codes of the update, Installation on request of a client and the
 * display during the dialog through the frame (design decisions 8 and 11).
 */
class UpdateApiTest {
    @TempDir
    lateinit var dir: File

    private val installer = FakeAppInstaller()

    private fun frame(
        ready: Boolean = true,
        clock: TestClock? = null,
        power: FakeDisplayPower = FakeDisplayPower(),
    ): TestFrame {
        if (ready) ReadyUpdate.seed(dir, notes = "Faster start\n<b>bold</b>", checkedAtMs = clock?.millis() ?: System.currentTimeMillis())
        val frame =
            TestFrame(
                dir,
                platform = FakePlatform(appVersion = "0.3.0", appVersionCode = 300),
                installer = installer,
                releases = FakeReleaseSource(),
                displayPower = power,
                clock = clock ?: io.github.tycho991.openframe.core.platform.Clock.System,
            )
        waitFor(message = "update state loaded") { frame.core.update.state.value.phase != UpdatePhase.IDLE || !ready }
        return frame
    }

    private fun json(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun TestFrame.install() = send("POST", "/api/v1/update/install")

    private fun code(body: String) =
        json(body)["error"]!!
            .jsonObject["code"]!!
            .jsonPrimitive.content

    /** Spec `app-update`: Update state in the API › Update ready. */
    @Test
    fun `Update ready`() {
        frame().use { f ->
            val res = f.get("/api/v1/update")
            assertEquals(200, res.statusCode())
            val body = json(res.body())
            assertEquals("0.3.0", body["installedVersion"]!!.jsonPrimitive.content)
            assertEquals("ready", body["phase"]!!.jsonPrimitive.content)
            val latest = body["latest"]!!.jsonObject
            assertEquals("0.4.0", latest["version"]!!.jsonPrimitive.content)
            assertEquals("Faster start\n<b>bold</b>", latest["notes"]!!.jsonPrimitive.content)
            assertEquals("update_found", body["lastCheck"]!!.jsonObject["result"]!!.jsonPrimitive.content)
            assertTrue(Instant.parse(body["lastCheck"]!!.jsonObject["at"]!!.jsonPrimitive.content) <= Instant.now())
            assertEquals("true", body["installAllowed"].toString())
            assertEquals("0.3.0", json(f.get("/api/v1/state").body())["appVersion"]!!.jsonPrimitive.content)
        }
    }

    /** Spec `app-update`: Update state in the API › Without token. */
    @Test
    fun `Without token`() {
        frame().use { f ->
            assertEquals(401, f.http.get("/api/v1/update").statusCode())
            assertEquals(401, f.http.request("POST", "/api/v1/update/check").statusCode())
            assertEquals(401, f.http.request("POST", "/api/v1/update/install").statusCode())
            assertTrue(installer.calls.isEmpty())
        }
    }

    /** Spec `app-update`: Installation on request of a client › Install on the frame; Fixed codes of the update. */
    @Test
    fun `Accepted, then in progress, then the pause after a cancel`() {
        frame().use { f ->
            val res = f.install()
            assertEquals(202, res.statusCode())
            assertEquals("installing", json(res.body())["phase"]!!.jsonPrimitive.content)
            waitFor(message = "dialog opened") { installer.calls.contains("commit 1") }
            val again = f.install()
            assertEquals(409, again.statusCode())
            assertEquals("install_in_progress", code(again.body()))
            installer.answer(1, InstallResult.Cancelled)
            val paused = f.install()
            assertEquals(409, paused.statusCode())
            assertEquals("install_cooldown", code(paused.body()))
            assertEquals(
                "install_cancelled",
                json(f.get("/api/v1/update").body())["error"]!!
                    .jsonObject["code"]!!
                    .jsonPrimitive.content,
            )
            assertTrue(
                f.core.log
                    .recent(1000)
                    .any { "requested by \"Test\" refused: install_cooldown" in it.message },
            )
        }
    }

    @Test
    fun `Nothing ready and not allowed`() {
        frame(ready = false).use { f ->
            val res = f.install()
            assertEquals(409, res.statusCode())
            assertEquals("update_not_ready", code(res.body()))
        }
        installer.allowed = false
        frame().use { f ->
            val res = f.install()
            assertEquals(409, res.statusCode())
            assertEquals("install_not_allowed", code(res.body()))
            assertEquals("false", json(f.get("/api/v1/update").body())["installAllowed"].toString())
        }
    }

    @Test
    fun `A check on request answers 202 with the state`() {
        frame(ready = false).use { f ->
            val res = f.send("POST", "/api/v1/update/check")
            assertEquals(202, res.statusCode())
            assertTrue(json(res.body())["phase"]!!.jsonPrimitive.content in setOf("checking", "idle"))
        }
    }

    /** Spec `app-update`: Fixed codes of the update: a code outside the contract's enums fails the contract check. */
    @Test
    fun `A code outside the enums fails the contract check`() {
        val refusal = """{"error":{"code":"install_later","message":"x"}}"""
        assertThrows(AssertionError::class.java) { Contract.check("POST", "/api/v1/update/install", 409, "application/json", refusal) }
        Contract.check("POST", "/api/v1/update/install", 409, "application/json", refusal.replace("install_later", "install_cooldown"))
        val state =
            """{"installedVersion":"0.3.0","phase":"idle","latest":null,"lastCheck":null,""" +
                """"error":{"code":"update_gone","message":"x"},"installAllowed":true}"""
        assertThrows(AssertionError::class.java) { Contract.check("GET", "/api/v1/update", 200, "application/json", state) }
        assertThrows(AssertionError::class.java) {
            Contract.check(
                "GET",
                "/api/v1/update",
                200,
                "application/json",
                state.replace("update_gone", "update_tls").replace("idle", "resting"),
            )
        }
        Contract.check("GET", "/api/v1/update", 200, "application/json", state.replace("update_gone", "update_tls"))
    }

    /**
     * Spec `app-update`: Installation on request of a client › Nothing ready and › Display off at night, through the
     * frame: a refused request leaves the display off, an accepted one wakes it and holds it until the dialog ends.
     */
    @Test
    fun `The display at night follows the update phase`() {
        val clock = TestClock(Instant.parse("2026-10-21T23:00:00Z"))
        val power = FakeDisplayPower().apply { screen(false) }
        frame(ready = false, clock = clock, power = power).use { f ->
            assertEquals(200, f.send("PATCH", "/api/v1/settings", """{"displaySchedule":{"enabled":true}}""").statusCode())
            waitFor(message = "schedule applied") { f.core.display.state.value.nextChange != null }
            assertEquals(409, f.install().statusCode())
            Thread.sleep(200)
            assertFalse(power.screenOn)
            assertEquals(emptyList<String>(), power.calls)
        }
        ReadyUpdate.seed(dir, checkedAtMs = clock.millis())
        frame(clock = clock, power = power).use { f ->
            waitFor(message = "display state") { f.core.display.state.value.nextChange != null }
            assertFalse(power.screenOn)
            assertEquals(202, f.install().statusCode())
            waitFor(message = "display woken and held") { power.screenOn && power.keptOn }
            assertEquals(listOf("on"), power.calls)
            waitFor(message = "dialog opened") { installer.calls.contains("commit 1") }
            installer.answer(1, InstallResult.Cancelled)
            waitFor(message = "hold released") { !power.keptOn }
            assertTrue(power.screenOn, "on for the minutes after waking")
        }
    }
}
