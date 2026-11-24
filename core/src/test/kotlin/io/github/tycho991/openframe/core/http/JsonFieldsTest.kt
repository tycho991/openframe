package io.github.tycho991.openframe.core.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The checks of single JSON fields that every hand-validated body uses (design decision 1 of `core-cleanup`). */
class JsonFieldsTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun `A switch is only a JSON true or false`() {
        for ((text, expected) in listOf("true" to true, "false" to false)) {
            val errors = LinkedHashMap<String, String>()
            assertEquals(expected, JsonFields.bool(json(text), "enabled", errors), text)
            assertEquals(emptyMap<String, String>(), errors, text)
        }
        for (value in listOf(json("\"true\""), json("\"TRUE\""), json("1"), JsonNull, json("[]"), json("{}"))) {
            val errors = LinkedHashMap<String, String>()
            assertEquals(null, JsonFields.bool(value, "enabled", errors), value.toString())
            assertEquals(mapOf("enabled" to "Expected true or false"), errors, value.toString())
        }
    }

    @Test
    fun `An integer is only an unquoted whole number in its range`() {
        val range = 5..1440
        // `1e1` is the number 10 in JSON, as JSON Schema's `integer` counts it; kept as the copies before read it.
        for ((text, expected) in listOf("20" to 20, "5" to 5, "1440" to 1440, "1e1" to 10)) {
            val errors = LinkedHashMap<String, String>()
            assertEquals(expected, JsonFields.int(json(text), "intervalMinutes", range, errors), text)
            assertEquals(emptyMap<String, String>(), errors, text)
        }
        for (text in listOf("\"20\"", "20.5", "4", "1441", "null", "true", "[]")) {
            val errors = LinkedHashMap<String, String>()
            assertEquals(null, JsonFields.int(json(text), "intervalMinutes", range, errors), text)
            assertEquals(mapOf("intervalMinutes" to "Expected an integer from 5 to 1440"), errors, text)
        }
    }

    @Test
    fun `Errors of several fields are collected in one map`() {
        val errors = LinkedHashMap<String, String>()
        JsonFields.bool(json("\"false\""), "removeVanished", errors)
        JsonFields.int(json("2"), "intervalMinutes", 5..1440, errors)
        JsonFields.bool(json("true"), "enabled", errors)
        assertEquals(setOf("removeVanished", "intervalMinutes"), errors.keys)
    }
}
