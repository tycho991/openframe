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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `display-schedule` › Schedule settings and Device administration as precondition. */
class DisplayScheduleSettingsTest {
    @TempDir
    lateinit var dir: File

    private val log = AppLog(TestClock())
    private val file get() = File(dir, "settings.json")
    private var allowed = true

    private fun repo(): SettingsRepository =
        SettingsRepository(JsonStore(file, StoredSettings.serializer(), TestScope(), log), "PFF_1024", log) { allowed }
            .also { it.load() }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private fun rejected(
        r: SettingsRepository,
        patch: String,
    ): Set<String> = assertThrows<ValidationException> { r.patch(json(patch)) }.fields.keys

    @Test
    fun `Defaults`() {
        val d = repo().settings.value.displaySchedule
        assertFalse(d.enabled)
        assertEquals(DisplayWindow("07:00", "22:00"), d.weekdays)
        assertEquals(DisplayWindow("08:00", "23:00"), d.weekend)
        assertEquals(5, d.wakeMinutes)
    }

    @Test
    fun `Invalid time`() {
        val r = repo()
        val before = r.settings.value
        assertEquals(setOf("displaySchedule.weekdays.off"), rejected(r, """{"displaySchedule":{"weekdays":{"off":"24:00"}}}"""))
        assertEquals(before, r.settings.value)
        assertEquals(0, r.version.value)
    }

    @Test
    fun `Only the weekend window`() {
        val r = repo()
        val before = r.settings.value.displaySchedule
        r.patch(json("""{"displaySchedule":{"weekend":{"on":"09:30"}}}"""))
        assertEquals(before.copy(weekend = DisplayWindow("09:30", "23:00")), r.settings.value.displaySchedule)
    }

    @Test
    fun `Times, minutes and unknown fields are checked`() {
        val r = repo()
        assertEquals(
            setOf(
                "displaySchedule.weekend.on",
                "displaySchedule.weekend.off",
                "displaySchedule.weekdays.on",
                "displaySchedule.weekdays.off",
                "displaySchedule.foo",
            ),
            rejected(
                r,
                """{"displaySchedule":{"weekend":{"on":"25:00","off":"7:00"},"weekdays":{"on":700,"off":"07:60"},"foo":1}}""",
            ),
        )
        assertEquals(setOf("displaySchedule.wakeMinutes"), rejected(r, """{"displaySchedule":{"wakeMinutes":0}}"""))
        assertEquals(setOf("displaySchedule.wakeMinutes"), rejected(r, """{"displaySchedule":{"wakeMinutes":121}}"""))
        assertEquals(setOf("displaySchedule.weekend.at"), rejected(r, """{"displaySchedule":{"weekend":{"at":"09:00"}}}"""))
        assertEquals(setOf("displaySchedule"), rejected(r, """{"displaySchedule":true}"""))
        assertEquals(setOf("displaySchedule.weekdays"), rejected(r, """{"displaySchedule":{"weekdays":"07:00"}}"""))
        assertEquals(setOf("displaySchedule.enabled"), rejected(r, """{"displaySchedule":{"enabled":"true"}}"""))
    }

    @Test
    fun `Bounds are accepted`() {
        val r = repo()
        r.patch(json("""{"displaySchedule":{"weekdays":{"on":"00:00","off":"23:59"},"wakeMinutes":120}}"""))
        r.patch(json("""{"displaySchedule":{"wakeMinutes":1}}"""))
        assertEquals(DisplayWindow("00:00", "23:59"), r.settings.value.displaySchedule.weekdays)
        assertEquals(1, r.settings.value.displaySchedule.wakeMinutes)
    }

    @Test
    fun `Settings stored without a schedule get the defaults and keep their other values`() {
        file.writeText(
            """{"values":{"name":"Hallway","intervalSeconds":60,"clock":{"enabled":false},"cacheBudgetMb":2000},"version":3}""",
        )
        val s = repo().settings.value
        assertEquals("Hallway", s.name)
        assertEquals(60, s.intervalSeconds)
        assertFalse(s.clock.enabled)
        assertEquals(2000, s.cacheBudgetMb)
        assertEquals(DisplayScheduleSettings(), s.displaySchedule)
    }

    @Test
    fun `The schedule survives a restart`() =
        runBlocking {
            val r = repo()
            r.patch(json("""{"displaySchedule":{"enabled":true,"weekend":{"on":"09:00"},"wakeMinutes":10}}"""))
            r.flush()
            val d = repo().settings.value.displaySchedule
            assertTrue(d.enabled)
            assertEquals(DisplayWindow("09:00", "23:00"), d.weekend)
            assertEquals(10, d.wakeMinutes)
        }

    @Test
    fun `Schedule without device administration`() {
        allowed = false
        val r = repo()
        assertEquals(setOf("displaySchedule.enabled"), rejected(r, """{"displaySchedule":{"enabled":true}}"""))
        assertFalse(r.settings.value.displaySchedule.enabled)
        assertEquals(0, r.version.value)
    }

    @Test
    fun `Times can be changed without device administration`() {
        allowed = false
        val r = repo()
        r.patch(json("""{"displaySchedule":{"enabled":false,"weekdays":{"on":"06:30"},"wakeMinutes":15}}"""))
        assertEquals("06:30", r.settings.value.displaySchedule.weekdays.on)
        assertEquals(15, r.settings.value.displaySchedule.wakeMinutes)
    }

    @Test
    fun `A stored schedule stays on after device administration is lost`() =
        runBlocking {
            val r = repo()
            r.patch(json("""{"displaySchedule":{"enabled":true}}"""))
            r.flush()
            allowed = false
            val restarted = repo()
            assertTrue(restarted.settings.value.displaySchedule.enabled)
            restarted.patch(json("""{"intervalSeconds":60,"displaySchedule":{"weekend":{"off":"23:30"}}}"""))
            assertTrue(restarted.settings.value.displaySchedule.enabled)
            // A client that sends all its settings repeats `enabled: true`; that switches nothing on.
            restarted.patch(json("""{"name":"Hall","displaySchedule":{"enabled":true,"wakeMinutes":7}}"""))
            assertEquals("Hall", restarted.settings.value.name)
            assertEquals(7, restarted.settings.value.displaySchedule.wakeMinutes)
            restarted.patch(json("""{"displaySchedule":{"enabled":false}}"""))
            assertEquals(setOf("displaySchedule.enabled"), rejected(restarted, """{"displaySchedule":{"enabled":true}}"""))
        }
}
