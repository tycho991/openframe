package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.health.Health
import io.github.tycho991.openframe.core.library.RenderSize
import io.github.tycho991.openframe.core.slideshow.SlideState
import io.github.tycho991.openframe.core.testing.FakeDisplayPower
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.Res
import io.github.tycho991.openframe.core.testing.TestFrame
import io.github.tycho991.openframe.core.testing.waitFor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Spec `slideshow-display`: Slideshow state through the API, Operating the slideshow through the API, Operating the
 * slideshow while the display is off and Slideshow API in safe mode, against the contract.
 */
class SlideshowApiTest {
    @TempDir
    lateinit var dir: File

    private var frame: TestFrame? = null

    @AfterEach
    fun tearDown() {
        frame?.close()
    }

    /** A frame with [count] photos, all of them rendered, showing the first one. */
    private fun frame(
        count: Int = 3,
        health: Health = Health.normal(),
    ): TestFrame {
        for (i in 1..count) Fixtures.copy("landscape.jpg", File(File(dir, "photos"), "p$i.jpg"))
        val f = TestFrame(dir, displayPower = FakeDisplayPower(), health = health).also { frame = it }
        if (count > 0 && !health.safe) {
            waitFor { f.core.slideshow.state.value is SlideState.Showing }
            waitFor {
                f.core.visiblePhotos.value
                    .all { f.core.cache.peek(it, RenderSize.DISPLAY) != null }
            }
        }
        return f
    }

    private fun TestFrame.shown() = core.slideshow.state.value as SlideState.Showing

    private fun TestFrame.control(action: String) = send("POST", "/api/v1/slideshow", """{"action":"$action"}""")

    private fun TestFrame.displayOff() {
        assertEquals(200, send("PUT", "/api/v1/display", """{"on":false}""").statusCode())
        waitFor { core.slideshow.resting.value }
    }

    private fun TestFrame.displayOn() {
        assertEquals(200, send("PUT", "/api/v1/display", """{"on":true}""").statusCode())
        waitFor { !core.slideshow.resting.value }
    }

    private fun json(res: Res): JsonObject = Json.parseToJsonElement(res.body()).jsonObject

    private fun JsonObject.flag(name: String) = this[name]!!.jsonPrimitive.boolean

    private fun JsonObject.photoId() = this["photo"]!!.jsonObject["id"]!!.jsonPrimitive.content

    private fun error(res: Res) = json(res)["error"]!!.jsonObject

    // Slideshow state through the API.

    @Test
    fun `Photo on screen`() {
        val f = frame()
        val id = f.shown().photo.id
        // An edited comment shows at once, without a photo change.
        assertEquals(200, f.send("PATCH", "/api/v1/photos/$id", """{"comment":"Evening light"}""").statusCode())
        val res = f.get("/api/v1/slideshow")
        assertEquals(200, res.statusCode())
        val state = json(res)
        val photo = state["photo"]!!.jsonObject
        assertEquals(id, photo["id"]!!.jsonPrimitive.content)
        assertEquals("Evening light", photo["comment"]!!.jsonPrimitive.content)
        assertTrue("presentation" in photo)
        assertTrue(
            photo["urls"]!!
                .jsonObject["display"]!!
                .jsonPrimitive.content
                .startsWith("/api/v1/photos/$id/image?"),
        )
        assertFalse(state.flag("paused"))
        assertFalse(state.flag("resting"))
    }

    @Test
    fun `Empty library`() {
        val f = frame(count = 0)
        waitFor { f.core.slideshow.state.value == SlideState.Empty }
        val state = json(f.get("/api/v1/slideshow"))
        assertEquals(JsonNull, state["photo"])
    }

    @Test
    fun `Display off`() {
        val f = frame()
        val id = f.shown().photo.id
        f.displayOff()
        val state = json(f.get("/api/v1/slideshow"))
        assertTrue(state.flag("resting"))
        assertEquals(id, state.photoId(), "the photo that stays on screen")
    }

    @Test
    fun `Without a token`() {
        val f = frame(count = 1)
        val seq = f.shown().seq
        assertEquals(401, f.http.get("/api/v1/slideshow").statusCode())
        assertEquals(401, f.http.request("POST", "/api/v1/slideshow", """{"action":"next"}""").statusCode())
        assertEquals(seq, f.shown().seq)
    }

    // Operating the slideshow through the API.

    @Test
    fun `Next photo from afar`() {
        val f = frame()
        val before = f.shown()
        val res = f.control("next")
        assertEquals(200, res.statusCode())
        val after = f.shown()
        assertEquals(before.seq + 1, after.seq)
        assertEquals(+1, after.step)
        assertNotEquals(before.photo.id, after.photo.id)
        assertEquals(after.photo.id, json(res).photoId(), "the answer contains the new photo")
        val back = f.control("previous")
        assertEquals(before.photo.id, json(back).photoId())
    }

    @Test
    fun `Pause from afar`() {
        val f = frame()
        val res = f.control("pause")
        assertEquals(200, res.statusCode())
        assertTrue(json(res).flag("paused"))
        assertTrue(f.core.slideshow.paused.value, "the hint on the frame follows this flow and offers resume")
        assertTrue(json(f.control("pause")).flag("paused"), "pause is idempotent")
    }

    @Test
    fun `Paused on the frame, resumed from afar`() {
        val f = frame()
        assertEquals(200, f.send("PATCH", "/api/v1/settings", """{"intervalSeconds":5}""").statusCode())
        f.core.slideshow.setPaused(true)
        waitFor { f.core.slideshow.paused.value }
        val seq = f.shown().seq
        val res = f.control("resume")
        assertFalse(json(res).flag("paused"))
        waitFor(timeoutMs = 8000, message = "the photos change after the interval again") { f.shown().seq > seq }
    }

    @Test
    fun `Unknown command`() {
        val f = frame()
        val seq = f.shown().seq
        val res = f.control("shuffle")
        assertEquals(400, res.statusCode())
        assertEquals(setOf("action"), error(res)["fields"]!!.jsonObject.keys)
        for (body in listOf("{}", """{"action":null}""", """{"action":1}""", """["next"]""")) {
            val invalid = f.send("POST", "/api/v1/slideshow", body)
            assertEquals(400, invalid.statusCode(), body)
        }
        val extra = f.send("POST", "/api/v1/slideshow", """{"action":"pause","photoId":"x"}""")
        assertEquals(400, extra.statusCode())
        assertEquals(setOf("photoId"), error(extra)["fields"]!!.jsonObject.keys)
        assertEquals(seq, f.shown().seq, "the slideshow is unchanged")
        assertFalse(f.core.slideshow.paused.value)
    }

    // Operating the slideshow while the display is off.

    @Test
    fun `Next photo at night`() {
        val f = frame()
        val before = f.shown()
        f.displayOff()
        for (action in listOf("next", "previous")) {
            val res = f.control(action)
            assertEquals(409, res.statusCode())
            assertEquals("display_off", error(res)["code"]!!.jsonPrimitive.content)
        }
        assertEquals(before.seq, f.shown().seq, "the photo stays while the display is off")
        // When the display comes on, the slideshow moves on once, as it does without the refused commands.
        f.displayOn()
        waitFor { f.shown().seq == before.seq + 1 }
        Thread.sleep(200)
        assertEquals(before.seq + 1, f.shown().seq)
    }

    @Test
    fun `Pause at night`() {
        val f = frame()
        val before = f.shown()
        f.displayOff()
        val res = f.control("pause")
        assertEquals(200, res.statusCode())
        assertTrue(json(res).flag("paused"))
        assertTrue(json(res).flag("resting"))
        f.displayOn()
        Thread.sleep(200)
        assertEquals(before.seq, f.shown().seq, "the paused photo stays on screen")
        assertEquals(before.photo.id, json(f.get("/api/v1/slideshow")).photoId())
    }

    // Slideshow API in safe mode.

    @Test
    fun `Next photo in safe mode`() {
        val f = frame(health = Health.safe(0))
        val started = System.nanoTime()
        val res = f.control("next")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(503, res.statusCode())
        assertEquals("safe_mode", error(res)["code"]!!.jsonPrimitive.content)
        assertTrue(elapsedMs < 1000, "at once, not after the wait for the slideshow: $elapsedMs ms")
        val read = f.get("/api/v1/slideshow")
        assertEquals(503, read.statusCode())
        assertEquals("safe_mode", error(read)["code"]!!.jsonPrimitive.content)
    }
}
