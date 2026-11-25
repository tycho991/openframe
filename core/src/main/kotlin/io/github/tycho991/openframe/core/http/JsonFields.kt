package io.github.tycho991.openframe.core.http

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The checks of single JSON fields for every hand-validated body (design decision 1 of `core-cleanup`). Each takes the
 * map of errors per field, so that a caller collects every error before it changes anything; the value of the wrong
 * JSON type, such as the string `"true"` for a switch, counts as invalid.
 */
internal object JsonFields {
    /** A JSON `true` or `false`; anything else, a string such as `"true"` included, puts "Expected true or false" under [key]. */
    fun bool(
        value: JsonElement,
        key: String,
        errors: MutableMap<String, String>,
    ): Boolean? {
        val b = (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        if (b == null) errors[key] = "Expected true or false"
        return b
    }

    /** An unquoted JSON integer in [range]; anything else puts "Expected an integer from a to b" under [key]. */
    fun int(
        value: JsonElement,
        key: String,
        range: IntRange,
        errors: MutableMap<String, String>,
    ): Int? {
        val n = (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (n == null || n !in range) {
            errors[key] = "Expected an integer from ${range.first} to ${range.last}"
            return null
        }
        return n
    }
}
