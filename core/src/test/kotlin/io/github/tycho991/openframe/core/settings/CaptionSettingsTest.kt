package io.github.tycho991.openframe.core.settings

import io.github.tycho991.openframe.core.ValidationException
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `frame-settings`, requirement "Caption settings". */
class CaptionSettingsTest {
    @TempDir
    lateinit var dir: File

    private val log = AppLog(TestClock())
    private val file get() = File(dir, "settings.json")

    private fun repo(): SettingsRepository =
        SettingsRepository(JsonStore(file, StoredSettings.serializer(), TestScope(), log), "PFF_1024", log).also { it.load() }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `Defaults without stored settings`() {
        assertEquals(CaptionSettings(albumTitle = false, place = false, position = Corner.BOTTOM_LEFT), repo().settings.value.caption)
    }

    @Test
    fun `Defaults with settings from before the caption existed`() {
        // Stored by the build before the caption, with a clock in the top left.
        file.writeText(
            """{"values":{"name":"Hallway","intervalSeconds":60,"clock":{"enabled":true,"position":"top-left",""" +
                """"sizePercent":150,"format":"12h"},"language":"de"},"version":4}""",
        )
        val s = repo().settings.value
        assertEquals(CaptionSettings(), s.caption)
        assertEquals("Hallway", s.name)
        assertEquals(60, s.intervalSeconds)
        assertEquals(FrameLanguage.DE, s.language)
        assertEquals(ClockSettings(true, Corner.TOP_LEFT, 150, ClockFormat.H12), s.clock, "a stored clock position loads unchanged")
    }

    @Test
    fun `Same corner as the clock`() =
        runBlocking {
            val r = repo()
            val changed = r.patch(json("""{"caption":{"position":"bottom-right"}}"""))
            assertEquals(Corner.BOTTOM_RIGHT, changed.caption.position)
            assertEquals(Corner.BOTTOM_RIGHT, changed.clock.position)
            r.flush()
            assertEquals(
                Corner.BOTTOM_RIGHT,
                repo()
                    .settings.value.caption.position,
                "stored",
            )
        }

    @Test
    fun `A partial change keeps the other caption fields`() {
        val r = repo()
        r.patch(json("""{"caption":{"albumTitle":true}}"""))
        val s = r.patch(json("""{"caption":{"place":true}}"""))
        assertEquals(CaptionSettings(albumTitle = true, place = true, position = Corner.BOTTOM_LEFT), s.caption)
    }

    @Test
    fun `Invalid corner names the field caption position`() {
        val r = repo()
        val e = assertThrows<ValidationException> { r.patch(json("""{"caption":{"position":"middle"},"intervalSeconds":60}""")) }
        assertEquals(setOf("caption.position"), e.fields.keys)
        assertEquals(30, r.settings.value.intervalSeconds, "nothing changed")
        for ((body, field) in listOf(
            """{"caption":{"place":"yes"}}""" to "caption.place",
            """{"caption":{"albumTitle":1}}""" to "caption.albumTitle",
            """{"caption":{"size":2}}""" to "caption.size",
            """{"caption":true}""" to "caption",
        )) {
            assertEquals(setOf(field), assertThrows<ValidationException> { r.patch(json(body)) }.fields.keys, body)
        }
    }
}
