package io.github.tycho991.openframe.core.http

import io.github.tycho991.openframe.core.testing.Contract
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

/** Der Harness selbst muss Abweichungen vom Vertrag erkennen. */
class ContractHarnessTest {
    private val info =
        """{"id":"f1","name":"Wohnzimmer","appVersion":"0.1.0","androidVersion":"6.0.1",""" +
            """"screen":{"width":1024,"height":600},"apiVersion":1}"""

    @Test
    fun `Gültige Antwort besteht`() {
        assertDoesNotThrow { Contract.check("GET", "/api/v1/info", 200, "application/json; charset=utf-8", info) }
        assertDoesNotThrow { Contract.check("POST", "/api/v1/pairing/code", 204, null, "") }
    }

    @Test
    fun `Falsches Feld scheitert`() {
        val wrong = info.replace("\"apiVersion\":1", "\"apiVersion\":\"eins\"")
        assertThrows<AssertionError> { Contract.check("GET", "/api/v1/info", 200, "application/json", wrong) }
        val extra = info.replace("}$".toRegex(), ",\"geheim\":true}")
        assertThrows<AssertionError> { Contract.check("GET", "/api/v1/info", 200, "application/json", extra) }
    }

    @Test
    fun `Nicht beschriebener Status oder Pfad scheitert`() {
        assertThrows<AssertionError> { Contract.check("GET", "/api/v1/info", 418, "application/json", "{}") }
        assertThrows<AssertionError> { Contract.check("GET", "/api/v1/unbekannt", 200, "application/json", "{}") }
    }

    @Test
    fun `Fehlerantworten über components-responses`() {
        assertDoesNotThrow {
            Contract.check("GET", "/api/v1/photos", 401, "application/json", """{"error":{"code":"unauthorized","message":"x"}}""")
        }
        assertThrows<AssertionError> { Contract.check("GET", "/api/v1/photos", 401, "application/json", """{"fehler":1}""") }
    }

    @Test
    fun `Operationen und öffentliche Routen`() {
        assertTrue("GET /api/v1/info" in Contract.operations())
        assertTrue(Contract.isPublic("GET", "/api/v1/info"))
        assertTrue(!Contract.isPublic("GET", "/api/v1/photos"))
    }
}
