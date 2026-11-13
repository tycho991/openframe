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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Spec `display-brightness` › Brightness setting, Permission as precondition and Automatic brightness needs a light
 * sensor, and spec `display-schedule` › Dim settings.
 */
class BrightnessSettingsTest {
    @TempDir
    lateinit var dir: File

    private val log = AppLog(TestClock())
    private val file get() = File(dir, "settings.json")
    private var support = BrightnessSupport(available = true, autoSupported = true)

    private fun repo(): SettingsRepository =
        SettingsRepository(
            JsonStore(file, StoredSettings.serializer(), TestScope(), log),
            "PFF_1024",
            log,
            brightnessSupport = { support },
        ).also { it.load() }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private fun rejected(
        r: SettingsRepository,
        patch: String,
    ): Set<String> = assertThrows<ValidationException> { r.patch(json(patch)) }.fields.keys

    @Test
    fun `Defaults`() {
        val s = repo().settings.value
        assertEquals(BrightnessSettings(BrightnessMode.SYSTEM, 80), s.brightness)
        assertNull(s.displaySchedule.weekdays.dim)
        assertNull(s.displaySchedule.weekend.dim)
        assertEquals(20, s.displaySchedule.dimLevel)
    }

    @Test
    fun `Invalid level`() {
        val r = repo()
        val before = r.settings.value
        assertEquals(setOf("brightness.level"), rejected(r, """{"brightness":{"level":0}}"""))
        assertEquals(before, r.settings.value)
    }

    @Test
    fun `Levels, modes and unknown fields are checked`() {
        val r = repo()
        assertEquals(setOf("brightness.level"), rejected(r, """{"brightness":{"level":101}}"""))
        assertEquals(setOf("brightness.mode"), rejected(r, """{"brightness":{"mode":"dim"}}"""))
        assertEquals(setOf("brightness.foo"), rejected(r, """{"brightness":{"foo":1}}"""))
        assertEquals(setOf("brightness"), rejected(r, """{"brightness":50}"""))
        assertEquals(setOf("displaySchedule.dimLevel"), rejected(r, """{"displaySchedule":{"dimLevel":0}}"""))
        assertEquals(setOf("displaySchedule.dimLevel"), rejected(r, """{"displaySchedule":{"dimLevel":101}}"""))
        assertEquals(setOf("displaySchedule.weekend.dim"), rejected(r, """{"displaySchedule":{"weekend":{"dim":"9:00"}}}"""))
        r.patch(json("""{"brightness":{"mode":"manual","level":1},"displaySchedule":{"dimLevel":100}}"""))
        assertEquals(BrightnessSettings(BrightnessMode.MANUAL, 1), r.settings.value.brightness)
        assertEquals(100, r.settings.value.displaySchedule.dimLevel)
    }

    @Test
    fun `Dim time outside the window`() {
        val r = repo()
        val before = r.settings.value
        assertEquals(setOf("displaySchedule.weekdays.dim"), rejected(r, """{"displaySchedule":{"weekdays":{"dim":"23:00"}}}"""))
        assertEquals(setOf("displaySchedule.weekdays.dim"), rejected(r, """{"displaySchedule":{"weekdays":{"dim":"07:00"}}}"""))
        assertEquals(setOf("displaySchedule.weekdays.dim"), rejected(r, """{"displaySchedule":{"weekdays":{"dim":"22:00"}}}"""))
        assertEquals(before, r.settings.value)
    }

    @Test
    fun `Window moved past the dim time`() {
        val r = repo()
        r.patch(json("""{"displaySchedule":{"weekdays":{"dim":"21:00"}}}"""))
        assertEquals(setOf("displaySchedule.weekdays.dim"), rejected(r, """{"displaySchedule":{"weekdays":{"off":"20:00"}}}"""))
        assertEquals(DisplayWindow("07:00", "22:00", "21:00"), r.settings.value.displaySchedule.weekdays)
    }

    @Test
    fun `Window across midnight`() {
        val r = repo()
        r.patch(json("""{"displaySchedule":{"weekdays":{"off":"01:00","dim":"23:30"}}}"""))
        assertEquals(DisplayWindow("07:00", "01:00", "23:30"), r.settings.value.displaySchedule.weekdays)
        r.patch(json("""{"displaySchedule":{"weekdays":{"dim":"00:30"}}}"""))
        assertEquals("00:30", r.settings.value.displaySchedule.weekdays.dim)
        assertEquals(setOf("displaySchedule.weekdays.dim"), rejected(r, """{"displaySchedule":{"weekdays":{"dim":"03:00"}}}"""))
    }

    @Test
    fun `Whole-day window takes any dim time but the on time`() {
        val r = repo()
        r.patch(json("""{"displaySchedule":{"weekend":{"on":"08:00","off":"08:00","dim":"07:00"}}}"""))
        assertEquals("07:00", r.settings.value.displaySchedule.weekend.dim)
        assertEquals(setOf("displaySchedule.weekend.dim"), rejected(r, """{"displaySchedule":{"weekend":{"dim":"08:00"}}}"""))
    }

    @Test
    fun `Null removes a dim time`() {
        val r = repo()
        r.patch(json("""{"displaySchedule":{"weekdays":{"dim":"20:30"}}}"""))
        r.patch(json("""{"displaySchedule":{"weekdays":{"dim":null}}}"""))
        assertNull(r.settings.value.displaySchedule.weekdays.dim)
    }

    @Test
    fun `Manual without permission`() {
        support = BrightnessSupport(available = false, autoSupported = true)
        val r = repo()
        assertEquals(setOf("brightness.mode"), rejected(r, """{"brightness":{"mode":"manual"}}"""))
        assertEquals(setOf("brightness.mode"), rejected(r, """{"brightness":{"mode":"auto"}}"""))
        assertEquals(BrightnessMode.SYSTEM, r.settings.value.brightness.mode)
    }

    @Test
    fun `Level alone and mode system are accepted without permission`() {
        support = BrightnessSupport(available = false, autoSupported = false)
        val r = repo()
        r.patch(json("""{"brightness":{"mode":"system","level":40},"displaySchedule":{"weekdays":{"dim":"20:30"}}}"""))
        assertEquals(BrightnessSettings(BrightnessMode.SYSTEM, 40), r.settings.value.brightness)
    }

    @Test
    fun `No light sensor`() {
        support = BrightnessSupport(available = true, autoSupported = false)
        val r = repo()
        r.patch(json("""{"brightness":{"mode":"manual"}}"""))
        assertEquals(setOf("brightness.mode"), rejected(r, """{"brightness":{"mode":"auto"}}"""))
        assertEquals(BrightnessMode.MANUAL, r.settings.value.brightness.mode)
    }

    @Test
    fun `Brightness and dim times survive a restart`() =
        runBlocking {
            val r = repo()
            r.patch(json("""{"brightness":{"mode":"auto"},"displaySchedule":{"weekend":{"dim":"21:00"},"dimLevel":30}}"""))
            r.flush()
            support = BrightnessSupport(available = false, autoSupported = false)
            val s = repo().settings.value
            assertEquals(BrightnessMode.AUTO, s.brightness.mode)
            assertEquals(DisplayWindow("08:00", "23:00", "21:00"), s.displaySchedule.weekend)
            assertEquals(30, s.displaySchedule.dimLevel)
        }

    @Test
    fun `Settings stored without brightness get the defaults and keep their other values`() {
        file.writeText(
            """{"values":{"name":"Hallway","intervalSeconds":60,"displaySchedule":{"enabled":true,"weekdays":{"on":"06:00","off":"21:00"}}},"version":3}""",
        )
        val s = repo().settings.value
        assertEquals("Hallway", s.name)
        assertEquals(60, s.intervalSeconds)
        assertEquals(DisplayWindow("06:00", "21:00"), s.displaySchedule.weekdays)
        assertEquals(BrightnessSettings(), s.brightness)
        assertEquals(20, s.displaySchedule.dimLevel)
    }
}
