package io.github.tycho991.openframe.core.http

import io.github.tycho991.openframe.core.testing.Contract
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

/** The harness itself must detect deviations from the contract. */
class ContractHarnessTest {
    private val info =
        """{"id":"f1","name":"Living room","appVersion":"0.1.0","androidVersion":"6.0.1",""" +
            """"screen":{"width":1024,"height":600},"apiVersion":1}"""

    @Test
    fun `Valid response passes`() {
        assertDoesNotThrow { Contract.check("GET", "/api/v1/info", 200, "application/json; charset=utf-8", info) }
        assertDoesNotThrow { Contract.check("POST", "/api/v1/pairing/code", 204, null, "") }
    }

    @Test
    fun `Wrong field fails`() {
        val wrong = info.replace("\"apiVersion\":1", "\"apiVersion\":\"one\"")
        assertThrows<AssertionError> { Contract.check("GET", "/api/v1/info", 200, "application/json", wrong) }
        val extra = info.replace("}$".toRegex(), ",\"secret\":true}")
        assertThrows<AssertionError> { Contract.check("GET", "/api/v1/info", 200, "application/json", extra) }
    }

    @Test
    fun `Undescribed status or path fails`() {
        assertThrows<AssertionError> { Contract.check("GET", "/api/v1/info", 418, "application/json", "{}") }
        assertThrows<AssertionError> { Contract.check("GET", "/api/v1/unknown", 200, "application/json", "{}") }
    }

    @Test
    fun `Error responses through components-responses`() {
        assertDoesNotThrow {
            Contract.check("GET", "/api/v1/photos", 401, "application/json", """{"error":{"code":"unauthorized","message":"x"}}""")
        }
        assertThrows<AssertionError> { Contract.check("GET", "/api/v1/photos", 401, "application/json", """{"oops":1}""") }
    }

    @Test
    fun `A body where the contract has none fails`() {
        assertThrows<AssertionError> { Contract.check("POST", "/api/v1/pairing/code", 204, "text/plain", "hello") }
        assertThrows<AssertionError> {
            Contract.check("POST", "/api/v1/pairing/code", 204, "application/octet-stream", "", bodyBytes = 3)
        }
    }

    @Test
    fun `Required headers are checked`() {
        val locked = """{"error":{"code":"locked","message":"x"}}"""
        assertThrows<AssertionError> {
            Contract.check("POST", "/api/v1/pairing", 429, "application/json", locked, headers = emptyMap())
        }
        assertThrows<AssertionError> {
            Contract.check("POST", "/api/v1/pairing", 429, "application/json", locked, headers = mapOf("retry-after" to listOf("soon")))
        }
        assertDoesNotThrow {
            Contract.check("POST", "/api/v1/pairing", 429, "application/json", locked, headers = mapOf("retry-after" to listOf("60")))
        }
    }

    @Test
    fun `Formats are checked`() {
        val client =
            """{"id":"a","name":"b","pairedAt":"yesterday","lastSeenAt":"2026-11-18T20:00:00Z",""" +
                """"current":true,"pairedVia":null,"invitedBy":null}"""
        assertThrows<AssertionError> { Contract.check("PATCH", "/api/v1/clients/a", 200, "application/json", client) }
        assertDoesNotThrow {
            Contract.check(
                "PATCH",
                "/api/v1/clients/a",
                200,
                "application/json",
                client.replace("yesterday", "2026-11-18T19:00:00Z"),
            )
        }
    }

    @Test
    fun `A 2xx answer to a request body outside the contract fails`() {
        val client =
            """{"id":"a","name":"b","pairedAt":"2026-11-18T19:00:00Z","lastSeenAt":"2026-11-18T20:00:00Z",""" +
                """"current":true,"pairedVia":null,"invitedBy":null}"""
        assertThrows<AssertionError> {
            Contract.check("PATCH", "/api/v1/clients/a", 200, "application/json", client, requestBody = """{"name":"b","admin":true}""")
        }
        assertDoesNotThrow {
            Contract.check("PATCH", "/api/v1/clients/a", 200, "application/json", client, requestBody = """{"name":"b"}""")
        }
    }

    @Test
    fun `Methods outside the contract are not checked`() {
        assertDoesNotThrow { Contract.check("HEAD", "/api/v1/info", 405, "application/json", "{}") }
        assertDoesNotThrow { Contract.check("TRACE", "/api/v1/info", 405, "application/json", "{}") }
    }

    @Test
    fun `Operations and public routes`() {
        assertTrue("GET /api/v1/info" in Contract.operations())
        assertTrue(Contract.isPublic("GET", "/api/v1/info"))
        assertTrue(!Contract.isPublic("GET", "/api/v1/photos"))
    }
}
