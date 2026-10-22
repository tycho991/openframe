package io.github.tycho991.openframe.core.nextcloud

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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `nextcloud-source`: Configuration, Secrecy of the credentials. */
class NextcloudConfigTest {
    @TempDir
    lateinit var dir: File

    private val log = AppLog(TestClock())

    private fun newStore() =
        NextcloudConfigStore(JsonStore(File(dir, "nextcloud.json"), StoredNextcloud.serializer(), TestScope(), log)).also { it.load() }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun fieldsOf(block: () -> Unit): Map<String, String> = assertThrows(ValidationException::class.java, block).fields

    @Test
    fun `Defaults without a file`() {
        val config = newStore().view()
        assertFalse(config.connected)
        assertEquals("/", config.folder)
        assertEquals(20, config.intervalMinutes)
        assertEquals(2_000, config.originalsBudgetMb)
        assertTrue(config.includeSubfolders)
        assertTrue(config.removeVanished)
    }

    @Test
    fun `Partial change is stored and read back after a restart`() {
        val store = newStore()
        store.patch(json("""{"folder":"Photos/Family/","intervalMinutes":45,"removeVanished":false}"""))
        runBlocking { store.flush() }
        val again = newStore().view()
        assertEquals("/Photos/Family", again.folder)
        assertEquals(45, again.intervalMinutes)
        assertFalse(again.removeVanished)
        assertTrue(again.includeSubfolders, "Fields that were not named keep their value")
    }

    @Test
    fun `Limits of the interval and the budget`() {
        val store = newStore()
        assertEquals(setOf("intervalMinutes"), fieldsOf { store.patch(json("""{"intervalMinutes":4}""")) }.keys)
        assertEquals(setOf("intervalMinutes"), fieldsOf { store.patch(json("""{"intervalMinutes":1441}""")) }.keys)
        assertEquals(setOf("intervalMinutes"), fieldsOf { store.patch(json("""{"intervalMinutes":"20"}""")) }.keys)
        assertEquals(setOf("originalsBudgetMb"), fieldsOf { store.patch(json("""{"originalsBudgetMb":99}""")) }.keys)
        assertEquals(setOf("originalsBudgetMb"), fieldsOf { store.patch(json("""{"originalsBudgetMb":50001}""")) }.keys)
        assertEquals(5, store.patch(json("""{"intervalMinutes":5}""")).intervalMinutes)
        assertEquals(1_440, store.patch(json("""{"intervalMinutes":1440}""")).intervalMinutes)
        assertEquals(100, store.patch(json("""{"originalsBudgetMb":100}""")).originalsBudgetMb)
        assertEquals(50_000, store.patch(json("""{"originalsBudgetMb":50000}""")).originalsBudgetMb)
    }

    @Test
    fun `A change with one wrong field changes nothing`() {
        val store = newStore()
        val fields = fieldsOf { store.patch(json("""{"folder":"/Photos","intervalMinutes":1,"colour":"red"}""")) }
        assertEquals(setOf("intervalMinutes", "colour"), fields.keys)
        assertEquals("/", store.view().folder)
    }

    @Test
    fun `Folder paths are normalized or refused`() {
        assertEquals("/", NextcloudConfigStore.normalizeFolder(""))
        assertEquals("/", NextcloudConfigStore.normalizeFolder("/"))
        assertEquals("/a/b", NextcloudConfigStore.normalizeFolder("//a//b/"))
        assertEquals("/Fotos 2025", NextcloudConfigStore.normalizeFolder(" Fotos 2025 "))
        assertEquals(null, NextcloudConfigStore.normalizeFolder("/a/../b"))
        assertEquals(null, NextcloudConfigStore.normalizeFolder("/a/./b"))
        assertEquals(null, NextcloudConfigStore.normalizeFolder("/a\u0000b"))
        assertEquals(null, NextcloudConfigStore.normalizeFolder("/" + "x".repeat(1_100)))
    }

    @Test
    fun `Only http and https addresses are accepted`() {
        assertEquals("https://cloud.example.org", NextcloudConfigStore.normalizeServerUrl(" https://cloud.example.org/ "))
        assertEquals("http://nas.local:8080/nextcloud", NextcloudConfigStore.normalizeServerUrl("http://nas.local:8080/nextcloud/"))
        for (bad in listOf(
            "ftp://x.org",
            "cloud.example.org",
            "https://",
            "https://user:pw@x.org",
            "https://x.org/?a=1",
            "https://x.org/#f",
            "",
        )) {
            assertEquals(setOf("serverUrl"), fieldsOf { NextcloudConfigStore.normalizeServerUrl(bad) }.keys, bad)
        }
    }

    @Test
    fun `Connect and disconnect`() {
        val store = newStore()
        store.patch(json("""{"folder":"/Photos"}"""))
        store.connect("https://cloud.example.org", "anna", "Anna", "app-password-1")
        assertTrue(store.view().connected)
        assertEquals("Anna", store.view().user)
        store.disconnect()
        val view = store.view()
        assertFalse(view.connected)
        assertEquals(null, view.user)
        assertEquals("/Photos", view.folder, "The options stay")
    }

    @Test
    fun `The password is nowhere but in the stored value`() {
        val secret = "s3cr3t-app-pw-Zq81"
        val store = newStore()
        store.connect("https://cloud.example.org", "anna", "Anna", secret)
        assertFalse(store.view().toString().contains(secret), "view")
        assertFalse(
            store.config.value
                .toString()
                .contains(secret),
            "toString of the stored value",
        )
        // Not in the error of a refused change either.
        val message = assertThrows(ValidationException::class.java) { store.patch(json("""{"intervalMinutes":1}""")) }.message.orEmpty()
        assertFalse(message.contains(secret))
        // The file holds it: that is the one place for it.
        runBlocking { store.flush() }
        assertTrue(File(dir, "nextcloud.json").readText().contains(secret))
    }
}
