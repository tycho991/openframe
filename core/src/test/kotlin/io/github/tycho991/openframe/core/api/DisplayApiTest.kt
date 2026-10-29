package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.testing.FakeDisplayPower
import io.github.tycho991.openframe.core.testing.TestClock
import io.github.tycho991.openframe.core.testing.TestFrame
import io.github.tycho991.openframe.core.testing.waitFor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

/**
 * Spec `display-schedule`: Display state in the API, Manual switch and Device administration as precondition, against
 * the contract. 2026-10-21 is a Wednesday; the clock stands still.
 */
class DisplayApiTest {
    @TempDir
    lateinit var dir: File

    private var frame: TestFrame? = null

    @AfterEach
    fun tearDown() {
        frame?.close()
    }

    private fun frame(
        at: String,
        power: FakeDisplayPower = FakeDisplayPower(),
        scheduleOn: Boolean = true,
    ): TestFrame {
        if (scheduleOn) {
            File(dir, "state").mkdirs()
            File(dir, "state/settings.json").writeText("""{"values":{"displaySchedule":{"enabled":true}}}""")
        }
        return TestFrame(dir, clock = TestClock(Instant.parse(at)), displayPower = power).also { frame = it }
    }

    private fun json(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    @Test
    fun `State at night`() {
        // The display is already off, as after the off time: no grace at the start.
        val power = FakeDisplayPower().apply { screen(false) }
        val f = frame("2026-10-21T23:00:00Z", power)
        waitFor { f.core.display.state.value.cause.api == "schedule" }
        val res = f.get("/api/v1/display")
        assertEquals(200, res.statusCode())
        assertEquals(
            json("""{"available":true,"on":false,"cause":"schedule","nextChange":"2026-10-22T07:00:00Z"}"""),
            json(res.body()),
        )
    }

    @Test
    fun `Without token`() {
        val power = FakeDisplayPower()
        val f = frame("2026-10-21T15:00:00Z", power)
        assertEquals(401, f.http.get("/api/v1/display").statusCode())
        assertEquals(401, f.http.request("PUT", "/api/v1/display", """{"on":false}""").statusCode())
        assertEquals(emptyList<String>(), power.calls)
    }

    @Test
    fun `Manual switch without device administration`() {
        val power = FakeDisplayPower(available = false)
        val f = frame("2026-10-21T15:00:00Z", power, scheduleOn = false)
        val res = f.send("PUT", "/api/v1/display", """{"on":false}""")
        assertEquals(409, res.statusCode())
        assertEquals("display_unavailable", json(res.body())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertTrue(power.screenOn, "the display stays on")
        assertEquals(emptyList<String>(), power.calls)
        assertEquals(
            json("""{"available":false,"on":true,"cause":"none","nextChange":null}"""),
            json(f.get("/api/v1/display").body()),
        )
    }

    @Test
    fun `Schedule without device administration`() {
        val f = frame("2026-10-21T15:00:00Z", FakeDisplayPower(available = false), scheduleOn = false)
        val res = f.send("PATCH", "/api/v1/settings", """{"displaySchedule":{"enabled":true}}""")
        assertEquals(400, res.statusCode())
        assertEquals(setOf("displaySchedule.enabled"), json(res.body())["error"]!!.jsonObject["fields"]!!.jsonObject.keys)
        assertEquals(false, f.core.settings.settings.value.displaySchedule.enabled)
    }

    @Test
    fun `Manual off by day holds until the next on time`() {
        val power = FakeDisplayPower()
        val f = frame("2026-10-21T15:00:00Z", power)
        val res = f.send("PUT", "/api/v1/display", """{"on":false}""")
        assertEquals(200, res.statusCode())
        assertEquals(
            json("""{"available":true,"on":false,"cause":"manual","nextChange":"2026-10-22T07:00:00Z"}"""),
            json(res.body()),
        )
        assertEquals(listOf("off"), power.calls)
        val again = f.send("PUT", "/api/v1/display", """{"on":true}""")
        assertEquals(true, json(again.body())["on"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(listOf("off", "on"), power.calls)
    }

    @Test
    fun `Invalid switch`() {
        val power = FakeDisplayPower()
        val f = frame("2026-10-21T15:00:00Z", power)
        for (body in listOf("{}", """{"on":"false"}""", """{"on":null}""", "[true]", """{"on":false,"until":"07:00"}""")) {
            assertEquals(400, f.send("PUT", "/api/v1/display", body).statusCode(), body)
        }
        val fields = json(f.send("PUT", "/api/v1/display", """{"on":false,"until":"07:00"}""").body())["error"]!!.jsonObject["fields"]
        assertEquals(setOf("until"), fields!!.jsonObject.keys)
        assertEquals(emptyList<String>(), power.calls)
    }
}
