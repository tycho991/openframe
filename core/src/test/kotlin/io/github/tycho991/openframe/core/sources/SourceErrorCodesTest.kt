package io.github.tycho991.openframe.core.sources

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.store.StoreJson
import io.github.tycho991.openframe.core.testing.Contract
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import tools.jackson.dataformat.yaml.YAMLMapper
import java.io.File

/** Spec `remote-sources`: Status (the error code from a fixed list); design decision 11 of `nextcloud-hardening`. */
class SourceErrorCodesTest {
    @TempDir
    lateinit var dir: File

    private val contractCodes: Set<String> by lazy {
        val doc = YAMLMapper().readTree(File(System.getProperty("openframe.openapi")))
        doc
            .path("components")
            .path("schemas")
            .path("SourceErrorCode")
            .path("enum")
            .values()
            .map { it.asString() }
            .toSet()
    }

    @Test
    fun `Every kind of failure has a value in the contract, and the contract has no other`() {
        for (kind in SourceException.Kind.entries) {
            assert(kind.code in contractCodes) { "The kind $kind has no value ${kind.code} in SourceErrorCode" }
        }
        assertEquals(contractCodes, SourceErrorCodes.ALL)
    }

    @Test
    fun `A failure that is no SourceException is unexpected`() {
        assertEquals("unexpected", SourceErrorCodes.of(IllegalStateException("boom")))
        assertEquals("limit", SourceErrorCodes.of(SourceException(SourceException.Kind.LIMIT, "x")))
    }

    @Test
    fun `A stored result without a code and one with an unknown code both load, the unknown one is served as null`() {
        val file = File(dir, "sources-status.json")
        file.writeText(
            """{"results":{"a":{"lastSyncAtMs":1,"lastSyncOk":false,"error":"old"},""" +
                """"b":{"lastSyncAtMs":2,"lastSyncOk":false,"error":"new","errorCode":"renamed_in_a_later_version"}}}""",
        )
        val store = SyncResultStore(JsonStore(file, StoredSyncResults.serializer(), TestScope(), AppLog(TestClock())))
        store.load()
        assertEquals("old", store.get("a").error)
        assertNull(store.get("a").errorCode)
        assertFalse(store.get("a").rejected)
        assertEquals("renamed_in_a_later_version", store.get("b").errorCode)
        assertNull(SourceErrorCodes.served(store.get("b").errorCode))
        assertEquals("unauthorized", SourceErrorCodes.served("unauthorized"))
        // What this version writes, an older one reads: the new fields are ignored there.
        val written = StoreJson.encodeToString(StoredSyncResult.serializer(), StoredSyncResult(1, false, "x", errorCode = "limit"))
        assertEquals("limit", StoreJson.decodeFromString(StoredSyncResult.serializer(), written).errorCode)
    }

    private fun status(errorCode: String) =
        """{"id":"s1a2b3c4d","kind":"nextcloud-share","serverUrl":"https://cloud.example.org","user":null,""" +
            """"settings":{"name":"Shared","enabled":true,"folder":"/","includeSubfolders":true,"removeVanished":true,""" +
            """"intervalMinutes":20,"originalsBudgetMb":2000},"status":{"syncing":false,"waiting":false,""" +
            """"lastSyncAt":"2026-05-01T10:15:30Z","ok":false,"error":"x","errorCode":$errorCode,"added":0,"updated":0,"removed":0}}"""

    @Test
    fun `The contract takes a known code and null, and refuses any other`() {
        assertDoesNotThrow { Contract.check("GET", "/api/v1/sources/s1a2b3c4d", 200, "application/json", status("\"unreachable\"")) }
        assertDoesNotThrow { Contract.check("GET", "/api/v1/sources/s1a2b3c4d", 200, "application/json", status("null")) }
        assertThrows<AssertionError> {
            Contract.check("GET", "/api/v1/sources/s1a2b3c4d", 200, "application/json", status("\"nonsense\""))
        }
        val login = """{"state":"failed","user":null,"sourceId":null,"error":"x","errorCode":"nonsense"}"""
        assertThrows<AssertionError> { Contract.check("GET", "/api/v1/sources/login/f1", 200, "application/json", login) }
        assertDoesNotThrow {
            Contract.check("GET", "/api/v1/sources/login/f1", 200, "application/json", login.replace("nonsense", "too_many_sources"))
        }
    }
}
