package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.platform.RawBrightness
import io.github.tycho991.openframe.core.platform.ScreenBrightness
import io.github.tycho991.openframe.core.settings.BrightnessMode
import io.github.tycho991.openframe.core.settings.BrightnessSettings
import io.github.tycho991.openframe.core.testing.FakeDisplayPower
import io.github.tycho991.openframe.core.testing.FakeScreenBrightness
import io.github.tycho991.openframe.core.testing.Res
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
 * Spec `display-schedule`: Display state in the API, Manual switch and Device administration as precondition; spec
 * `display-brightness`: Brightness now, Atomic display requests, Permission as precondition and Brightness in the
 * display state; against the contract. 2026-10-21 is a Wednesday; the clock stands still.
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
        brightness: ScreenBrightness = ScreenBrightness.None,
        settings: String = """{"displaySchedule":{"enabled":true}}""",
    ): TestFrame {
        if (scheduleOn) {
            File(dir, "state").mkdirs()
            File(dir, "state/settings.json").writeText("""{"values":$settings}""")
        }
        return TestFrame(dir, clock = TestClock(Instant.parse(at)), displayPower = power, screenBrightness = brightness)
            .also { frame = it }
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
            json(
                """{"available":true,"on":false,"cause":"schedule","nextChange":"2026-10-22T07:00:00Z","brightness":{"available":false,"autoSupported":false,"level":100,"auto":false,"dimmed":false}}""",
            ),
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
            json(
                """{"available":false,"on":true,"cause":"none","nextChange":null,"brightness":{"available":false,"autoSupported":false,"level":100,"auto":false,"dimmed":false}}""",
            ),
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
            json(
                """{"available":true,"on":false,"cause":"manual","nextChange":"2026-10-22T07:00:00Z","brightness":{"available":false,"autoSupported":false,"level":100,"auto":false,"dimmed":false}}""",
            ),
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

    private fun code(res: Res) = json(res.body())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    private val dimmedEvening =
        """{"displaySchedule":{"enabled":true,"weekdays":{"on":"07:00","off":"22:00","dim":"21:00"}},"brightness":{"mode":"manual","level":80}}"""

    @Test
    fun `Brightness now by day`() {
        val screen = FakeScreenBrightness()
        val f = frame("2026-10-21T12:00:00Z", brightness = screen, settings = """{"brightness":{"mode":"auto","level":80}}""")
        val res = f.send("PUT", "/api/v1/display", """{"brightness":60}""")
        assertEquals(200, res.statusCode())
        assertEquals(
            60,
            json(res.body())["brightness"]!!
                .jsonObject["level"]!!
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals(RawBrightness(153, auto = false), screen.current)
        assertEquals(BrightnessSettings(BrightnessMode.MANUAL, 60), f.core.settings.settings.value.brightness)
    }

    @Test
    fun `Brightness now during a dim period`() {
        val screen = FakeScreenBrightness()
        val f = frame("2026-10-21T21:30:00Z", brightness = screen, settings = dimmedEvening)
        waitFor { screen.current == RawBrightness(51, auto = false) }
        val res = f.send("PUT", "/api/v1/display", """{"brightness":50}""")
        assertEquals(200, res.statusCode())
        assertEquals(
            json("""{"available":true,"autoSupported":true,"level":50,"auto":false,"dimmed":true}"""),
            json(res.body())["brightness"],
        )
        assertEquals(BrightnessSettings(BrightnessMode.MANUAL, 80), f.core.settings.settings.value.brightness)
    }

    @Test
    fun `On and brightness together`() {
        val power = FakeDisplayPower()
        val screen = FakeScreenBrightness()
        val f = frame("2026-10-21T12:00:00Z", power, brightness = screen)
        f.send("PUT", "/api/v1/display", """{"on":false}""")
        val res = f.send("PUT", "/api/v1/display", """{"on":true,"brightness":30}""")
        assertEquals(200, res.statusCode())
        assertTrue(power.screenOn)
        assertEquals(RawBrightness(77, auto = false), screen.current)
    }

    @Test
    fun `Brightness now without permission`() {
        val power = FakeDisplayPower()
        val screen = FakeScreenBrightness(available = false)
        val f = frame("2026-10-21T12:00:00Z", power, brightness = screen)
        val res = f.send("PUT", "/api/v1/display", """{"on":false,"brightness":30}""")
        assertEquals(409, res.statusCode())
        assertEquals("brightness_unavailable", code(res))
        assertTrue(power.screenOn, "the display is not switched either")
        assertEquals(emptyList<RawBrightness>(), screen.writes)
    }

    @Test
    fun `Display not available`() {
        val power = FakeDisplayPower(available = false)
        val screen = FakeScreenBrightness()
        val f = frame("2026-10-21T12:00:00Z", power, scheduleOn = false, brightness = screen)
        val res = f.send("PUT", "/api/v1/display", """{"on":false,"brightness":30}""")
        assertEquals(409, res.statusCode())
        assertEquals("display_unavailable", code(res))
        assertEquals(emptyList<RawBrightness>(), screen.writes)
    }

    @Test
    fun `Invalid brightness`() {
        val power = FakeDisplayPower()
        val screen = FakeScreenBrightness()
        val f = frame("2026-10-21T12:00:00Z", power, brightness = screen)
        for (body in listOf("""{"brightness":0}""", """{"brightness":101}""", """{"brightness":"50"}""", """{"brightness":null}""")) {
            val res = f.send("PUT", "/api/v1/display", body)
            assertEquals(400, res.statusCode(), body)
            assertEquals(setOf("brightness"), json(res.body())["error"]!!.jsonObject["fields"]!!.jsonObject.keys, body)
        }
        assertEquals(400, f.send("PUT", "/api/v1/display", """{"on":false,"brightness":101}""").statusCode())
        assertTrue(power.screenOn, "an invalid level does not switch the display either")
        assertEquals(emptyList<RawBrightness>(), screen.writes)
    }

    @Test
    fun `Manual mode without permission`() {
        val f = frame("2026-10-21T12:00:00Z", brightness = FakeScreenBrightness(available = false))
        val res = f.send("PATCH", "/api/v1/settings", """{"brightness":{"mode":"manual"}}""")
        assertEquals(400, res.statusCode())
        assertEquals(setOf("brightness.mode"), json(res.body())["error"]!!.jsonObject["fields"]!!.jsonObject.keys)
    }

    @Test
    fun `State in the evening`() {
        val screen = FakeScreenBrightness()
        val f = frame("2026-10-21T21:30:00Z", brightness = screen, settings = dimmedEvening)
        waitFor { f.core.display.state.value.brightness.dimmed }
        assertEquals(
            json("""{"available":true,"autoSupported":true,"level":20,"auto":false,"dimmed":true}"""),
            json(f.get("/api/v1/display").body())["brightness"],
        )
    }

    @Test
    fun `State without permission`() {
        val screen = FakeScreenBrightness(available = false, value = RawBrightness(128, auto = false))
        val f = frame("2026-10-21T12:00:00Z", brightness = screen)
        assertEquals(
            json("""{"available":false,"autoSupported":true,"level":50,"auto":false,"dimmed":false}"""),
            json(f.get("/api/v1/display").body())["brightness"],
        )
    }

    @Test
    fun `Settings carry the brightness and the dim times`() {
        val f = frame("2026-10-21T12:00:00Z", brightness = FakeScreenBrightness(), settings = dimmedEvening)
        val s = json(f.get("/api/v1/settings").body())
        assertEquals(json("""{"mode":"manual","level":80}"""), s["brightness"])
        assertEquals(
            "21:00",
            s["displaySchedule"]!!
                .jsonObject["weekdays"]!!
                .jsonObject["dim"]!!
                .jsonPrimitive.content,
        )
        assertEquals("null", s["displaySchedule"]!!.jsonObject["weekend"]!!.jsonObject["dim"].toString())
        val res = f.send("PATCH", "/api/v1/settings", """{"displaySchedule":{"weekdays":{"dim":null},"dimLevel":30}}""")
        assertEquals(200, res.statusCode())
    }
}
