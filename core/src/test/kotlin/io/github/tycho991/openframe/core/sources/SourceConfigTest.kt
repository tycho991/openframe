package io.github.tycho991.openframe.core.sources

import io.github.tycho991.openframe.core.ValidationException
import io.github.tycho991.openframe.core.http.HttpException
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `remote-sources`: Several sources, Configuration; `nextcloud-source`: Secrecy of the credentials. */
class SourceConfigTest {
    @TempDir
    lateinit var dir: File

    private val log = AppLog(TestClock())

    private fun newStore() =
        SourceConfigStore(JsonStore(File(dir, "sources.json"), StoredSources.serializer(), TestScope(), log)).also { it.load() }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun fieldsOf(block: () -> Unit): Map<String, String> = assertThrows(ValidationException::class.java, block).fields

    private fun account(
        id: String = "s00000001",
        password: String = "app-pw",
    ) = StoredSource(
        id,
        "nextcloud-account",
        "cloud.example.org",
        Connection.Account("https://cloud.example.org", "anna", "Anna", password),
    )

    @Test
    fun `No file means no sources, and the defaults of a new one`() {
        val store = SourceConfigStore(JsonStore(File(dir, "sources.json"), StoredSources.serializer(), TestScope(), log))
        assertEquals(SourceConfigStore.Loaded.MISSING, store.load(), "no sources.json")
        assertEquals(emptyList<StoredSource>(), store.sources.value)
        val added = store.add(account())
        assertEquals("", added.folder, "no default folder for an account")
        assertEquals(20, added.intervalMinutes)
        assertEquals(2_000, added.originalsBudgetMb)
        assertTrue(added.enabled && added.includeSubfolders && added.removeVanished)
        assertFalse(added.ready, "not ready without a folder")
    }

    @Test
    fun `A file that is there is read, one that cannot be read says so`() {
        val store = newStore()
        store.add(account())
        runBlocking { store.flush() }
        assertEquals(SourceConfigStore.Loaded.READ, newStore().load())
        File(dir, "sources.json").writeText("{ broken")
        val broken = SourceConfigStore(JsonStore(File(dir, "sources.json"), StoredSources.serializer(), TestScope(), log))
        assertEquals(SourceConfigStore.Loaded.UNREADABLE, broken.load())
        assertEquals(emptyList<StoredSource>(), broken.sources.value)
        assertEquals("{ broken", File(dir, "sources.json").readText(), "the file stays in place")
        assertEquals(listOf("sources.json"), dir.list()!!.filter { it.startsWith("sources.json") })
    }

    @Test
    fun `Partial change is stored and read back after a restart`() {
        val store = newStore()
        store.add(account())
        store.patch("s00000001", json("""{"folder":"Photos/Family/","intervalMinutes":45,"removeVanished":false,"name":" Family "}"""))
        runBlocking { store.flush() }
        val again = newStore().get("s00000001")!!
        assertEquals("/Photos/Family", again.folder)
        assertEquals(45, again.intervalMinutes)
        assertFalse(again.removeVanished)
        assertEquals("Family", again.name)
        assertTrue(again.includeSubfolders, "Fields that were not named keep their value")
        assertEquals(Connection.Account("https://cloud.example.org", "anna", "Anna", "app-pw"), again.connection)
    }

    @Test
    fun `Limits of the interval and the budget`() {
        val store = newStore()
        store.add(account())
        val id = "s00000001"
        assertEquals(setOf("intervalMinutes"), fieldsOf { store.patch(id, json("""{"intervalMinutes":4}""")) }.keys)
        assertEquals(setOf("intervalMinutes"), fieldsOf { store.patch(id, json("""{"intervalMinutes":1441}""")) }.keys)
        assertEquals(setOf("intervalMinutes"), fieldsOf { store.patch(id, json("""{"intervalMinutes":"20"}""")) }.keys)
        assertEquals(setOf("originalsBudgetMb"), fieldsOf { store.patch(id, json("""{"originalsBudgetMb":99}""")) }.keys)
        assertEquals(setOf("originalsBudgetMb"), fieldsOf { store.patch(id, json("""{"originalsBudgetMb":50001}""")) }.keys)
        assertEquals(5, store.patch(id, json("""{"intervalMinutes":5}""")).intervalMinutes)
        assertEquals(1_440, store.patch(id, json("""{"intervalMinutes":1440}""")).intervalMinutes)
        assertEquals(100, store.patch(id, json("""{"originalsBudgetMb":100}""")).originalsBudgetMb)
        assertEquals(50_000, store.patch(id, json("""{"originalsBudgetMb":50000}""")).originalsBudgetMb)
    }

    @Test
    fun `Names, switches and folders are checked`() {
        val store = newStore()
        store.add(account())
        val id = "s00000001"
        assertEquals(setOf("name"), fieldsOf { store.patch(id, json("""{"name":"  "}""")) }.keys)
        assertEquals(setOf("name"), fieldsOf { store.patch(id, json("""{"name":"${"x".repeat(65)}"}""")) }.keys)
        assertEquals(setOf("name"), fieldsOf { store.patch(id, json("""{"name":"a\u0007b"}""")) }.keys)
        assertEquals(setOf("name"), fieldsOf { store.patch(id, json("""{"name":42}""")) }.keys)
        assertEquals(setOf("enabled"), fieldsOf { store.patch(id, json("""{"enabled":"yes"}""")) }.keys)
        assertEquals(setOf("folder"), fieldsOf { store.patch(id, json("""{"folder":"/a/../b"}""")) }.keys)
        assertEquals("x".repeat(64), store.patch(id, json("""{"name":"${"x".repeat(64)}"}""")).name)
    }

    @Test
    fun `A change with an unknown field changes nothing`() {
        val store = newStore()
        store.add(account())
        val fields = fieldsOf { store.patch("s00000001", json("""{"folder":"/Photos","intervalMinutes":1,"colour":"red"}""")) }
        assertEquals(setOf("intervalMinutes", "colour"), fields.keys)
        assertEquals("Unknown field", fields["colour"])
        assertEquals("", store.get("s00000001")!!.folder)
    }

    @Test
    fun `A change of an unknown source is a 404`() {
        assertEquals(404, assertThrows(HttpException::class.java) { newStore().patch("nope", json("""{"enabled":false}""")) }.status)
    }

    @Test
    fun `Eight sources at most, the ninth is refused with too_many_sources`() {
        val store = newStore()
        repeat(StoredSource.MAX_SOURCES) { store.add(account(id = store.newId())) }
        val refused = assertThrows(HttpException::class.java) { store.add(account(id = store.newId())) }
        assertEquals(409, refused.status)
        assertEquals("too_many_sources", refused.code)
        assertEquals(8, store.sources.value.size)
        // Removing one makes room again.
        store.remove(
            store.sources.value
                .first()
                .id,
        )
        store.add(account(id = store.newId()))
        assertEquals(8, store.sources.value.size)
    }

    @Test
    fun `New IDs are s and eight hex digits and fit the photo IDs of the contract`() {
        val store = newStore()
        val ids = (1..50).map { store.newId() }
        assertTrue(ids.all { Regex("s[0-9a-f]{8}").matches(it) }, ids.toString())
    }

    @Test
    fun `Removing a source forgets it and its secrets`() {
        val store = newStore()
        store.add(account(password = "s3cr3t-Rm81"))
        assertEquals("s00000001", store.remove("s00000001")?.id)
        assertNull(store.remove("s00000001"))
        runBlocking { store.flush() }
        assertFalse(File(dir, "sources.json").readText().contains("s3cr3t-Rm81"))
    }

    @Test
    fun `Folder paths are normalized or refused`() {
        assertEquals("/", SourceConfigStore.normalizeFolder(""))
        assertEquals("/", SourceConfigStore.normalizeFolder("/"))
        assertEquals("/a/b", SourceConfigStore.normalizeFolder("//a//b/"))
        assertEquals("/Fotos 2025", SourceConfigStore.normalizeFolder(" Fotos 2025 "))
        assertEquals(null, SourceConfigStore.normalizeFolder("/a/../b"))
        assertEquals(null, SourceConfigStore.normalizeFolder("/a/./b"))
        assertEquals(null, SourceConfigStore.normalizeFolder("/a\u0000b"))
        assertEquals(null, SourceConfigStore.normalizeFolder("/" + "x".repeat(1_100)))
    }

    @Test
    fun `Only http and https addresses are accepted`() {
        assertEquals("https://cloud.example.org", SourceConfigStore.normalizeServerUrl(" https://cloud.example.org/ "))
        assertEquals("http://nas.local:8080/nextcloud", SourceConfigStore.normalizeServerUrl("http://nas.local:8080/nextcloud/"))
        assertEquals("https://Cloud.example.org", SourceConfigStore.normalizeServerUrl("Https://Cloud.example.org"), "a capitalized scheme")
        assertEquals("http://nas", SourceConfigStore.normalizeServerUrl("HTTP://nas"))
        for (bad in listOf(
            "ftp://x.org",
            "cloud.example.org",
            "https://",
            "https://user:pw@x.org",
            "https://x.org/?a=1",
            "https://x.org/#f",
            "",
        )) {
            assertEquals(setOf("serverUrl"), fieldsOf { SourceConfigStore.normalizeServerUrl(bad) }.keys, bad)
        }
    }

    @Test
    fun `The same server with http and https is one server`() {
        assertEquals(
            SourceConfigStore.serverKey("http://Cloud.example.org/nc"),
            SourceConfigStore.serverKey("https://cloud.example.org/nc/"),
        )
        assertFalse(SourceConfigStore.serverKey("https://cloud.example.org") == SourceConfigStore.serverKey("https://other.example.org"))
    }

    @Test
    fun `The passwords and the share token are nowhere but in the stored file`() {
        val appPassword = "s3cr3t-app-pw-Zq81"
        val sharePassword = "s3cr3t-share-pw-Lk42"
        val token = "ShareTokenQw73"
        val store = newStore()
        store.add(account(password = appPassword))
        store.add(
            StoredSource(
                "s00000002",
                "nextcloud-share",
                "Holiday",
                Connection.Share("https://cloud.example.org", token, sharePassword),
                folder = "/",
            ),
        )
        val texts = store.sources.value.map { it.toString() } + store.sources.value.map { it.connection.toString() }
        for (text in texts) {
            for (secret in listOf(appPassword, sharePassword, token)) assertFalse(text.contains(secret), "$secret in $text")
        }
        // Not in the error of a refused change either.
        val message =
            assertThrows(ValidationException::class.java) { store.patch("s00000002", json("""{"intervalMinutes":1}""")) }.message.orEmpty()
        assertFalse(message.contains(sharePassword) || message.contains(token))
        // The file holds them: that is the one place for them.
        runBlocking { store.flush() }
        val file = File(dir, "sources.json").readText()
        assertTrue(file.contains(appPassword) && file.contains(sharePassword) && file.contains(token))
    }
}
