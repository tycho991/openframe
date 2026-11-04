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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `frame-settings`. */
class SettingsRepositoryTest {
    @TempDir
    lateinit var dir: File

    private val log = AppLog(TestClock())
    private val file get() = File(dir, "settings.json")

    private fun repo(): SettingsRepository =
        SettingsRepository(JsonStore(file, StoredSettings.serializer(), TestScope(), log), "PFF_1024", log).also { it.load() }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `First start`() {
        val settings = repo().settings.value
        assertEquals(Settings.defaults("PFF_1024"), settings)
        assertEquals("PFF_1024", settings.name)
        assertEquals(30, settings.intervalSeconds)
        assertEquals(Order.SHUFFLE, settings.order)
        assertEquals(Transition.FADE, settings.transition)
        // Scenario "Default fill mode" › "New frame".
        assertEquals(FillMode.AUTO, settings.fillMode)
        assertEquals(Orientation.LANDSCAPE, settings.orientation)
        assertEquals(FrameLanguage.AUTO, settings.language)
        assertEquals(1000, settings.cacheBudgetMb)
    }

    private fun backedUpRepo(): SettingsRepository =
        SettingsRepository(JsonStore(file, StoredSettings.serializer(), TestScope(), log, backupOnLoad = true), "PFF_1024", log)
            .also { it.load() }

    private fun brokenCopies() = dir.list().orEmpty().filter { it.startsWith("settings.json.broken-") }

    /** Spec `admin-access`: Local-only mode › Damaged settings file; `frame-settings`: Durable, atomic storage. */
    @Test
    fun `A damaged settings file with a copy of the last start keeps its values with local-only mode on`() {
        runBlocking {
            backedUpRepo().apply {
                patch(json("""{"name":"Hallway","intervalSeconds":60}"""))
                flush()
            }
        }
        assertEquals(false, backedUpRepo().localOnly.value, "the start writes the copy")
        assertTrue(File(dir, "settings.json.bak").isFile)
        file.writeText("{ damaged")

        val repo = backedUpRepo()
        assertEquals("Hallway", repo.settings.value.name)
        assertEquals(60, repo.settings.value.intervalSeconds)
        assertTrue(repo.localOnly.value)
        runBlocking { repo.flush() }
        assertEquals("{ damaged", file.readText(), "neither renamed nor rewritten")
        assertEquals(emptyList<String>(), brokenCopies())

        repo.patch(json("""{"intervalSeconds":90}"""))
        runBlocking { repo.flush() }
        assertEquals(1, brokenCopies().size)
        assertEquals(90, backedUpRepo().settings.value.intervalSeconds)
    }

    @Test
    fun `A damaged settings file without a copy gives defaults with local-only mode on`() {
        file.writeText("""{"values":{"name":"Hallway"},"localOnly":false,"version":3 damaged""")
        val repo = backedUpRepo()
        assertEquals(Settings.defaults("PFF_1024"), repo.settings.value)
        assertTrue(repo.localOnly.value)
        runBlocking { repo.flush() }
        assertTrue(file.readText().endsWith("damaged"))

        // Turning it off on the frame writes the file and keeps the damaged one.
        repo.setLocalOnly(false)
        runBlocking { repo.flush() }
        assertEquals(1, brokenCopies().size)
        assertEquals(false, backedUpRepo().localOnly.value)
    }

    @Test
    fun `Update of a frame in fill`() {
        // Stored by the version before `auto`, which writes every value.
        file.writeText(
            """{"values":{"name":"Hallway","intervalSeconds":30,"order":"shuffle","transition":"fade",""" +
                """"transitionDurationMs":1500,"fillMode":"fill","orientation":"landscape"},"version":3}""",
        )
        assertEquals(FillMode.FILL, repo().settings.value.fillMode)
    }

    @Test
    fun `Fill mode auto is accepted, an unknown one names the field`() {
        val r = repo()
        r.patch(json("""{"fillMode":"fit"}"""))
        assertEquals(FillMode.AUTO, r.patch(json("""{"fillMode":"auto"}""")).fillMode)
        val e = assertThrows<ValidationException> { r.patch(json("""{"fillMode":"cover"}""")) }
        assertEquals(setOf("fillMode"), e.fields.keys)
        assertEquals(FillMode.AUTO, r.settings.value.fillMode)
    }

    @Test
    fun `Update with a new setting`() {
        // Stored by an older version that did not yet know cacheBudgetMb and orientation.
        file.writeText("""{"values":{"name":"Hallway","intervalSeconds":60,"obsolete":1},"version":7}""")
        val r = repo()
        assertEquals("Hallway", r.settings.value.name)
        assertEquals(60, r.settings.value.intervalSeconds)
        assertEquals(1000, r.settings.value.cacheBudgetMb)
        assertEquals(Orientation.LANDSCAPE, r.settings.value.orientation)
        assertEquals(7, r.version.value)
    }

    @Test
    fun `Invalid stored value falls back to the default`() {
        file.writeText("""{"values":{"intervalSeconds":1,"order":"chronological"}}""")
        val s = repo().settings.value
        assertEquals(30, s.intervalSeconds)
        assertEquals(Order.CHRONOLOGICAL, s.order)
    }

    @Test
    fun `Invalid interval`() {
        val r = repo()
        val e = assertThrows<ValidationException> { r.patch(json("""{"intervalSeconds":1,"clock":{"enabled":false}}""")) }
        assertEquals(setOf("intervalSeconds"), e.fields.keys)
        assertTrue(r.settings.value.clock.enabled, "The clock stays unchanged as well")
        assertEquals(0, r.version.value)
    }

    @Test
    fun `Unknown field`() {
        val r = repo()
        val e = assertThrows<ValidationException> { r.patch(json("""{"color":"red","clock":{"blink":true}}""")) }
        assertEquals(setOf("color", "clock.blink"), e.fields.keys)
    }

    @Test
    fun `Local only is not a field of the API`() {
        val e = assertThrows<ValidationException> { repo().patch(json("""{"localOnly":true}""")) }
        assertEquals(setOf("localOnly"), e.fields.keys)
    }

    @Test
    fun `Types and ranges are checked`() {
        val e =
            assertThrows<ValidationException> {
                repo().patch(
                    json(
                        """{"name":"","order":"random","transitionDurationMs":5001,"fillMode":1,""" +
                            """"clock":{"sizePercent":"100","format":"36h","enabled":"yes"},"cacheBudgetMb":99,"intervalSeconds":30.5}""",
                    ),
                )
            }
        assertEquals(
            setOf(
                "name",
                "order",
                "transitionDurationMs",
                "fillMode",
                "clock.sizePercent",
                "clock.format",
                "clock.enabled",
                "cacheBudgetMb",
                "intervalSeconds",
            ),
            e.fields.keys,
        )
    }

    @Test
    fun `Change only the interval and restart after a change`() =
        runBlocking {
            val r = repo()
            val before = r.settings.value
            r.patch(json("""{"intervalSeconds":60}"""))
            assertEquals(before.copy(intervalSeconds = 60), r.settings.value)
            assertEquals(1, r.version.value)
            r.flush()
            val restarted = repo()
            assertEquals(60, restarted.settings.value.intervalSeconds)
            assertEquals(1, restarted.version.value)
        }

    @Test
    fun `Local only is stored`() =
        runBlocking {
            val r = repo()
            r.setLocalOnly(true)
            r.flush()
            assertTrue(repo().localOnly.value)
        }
}
