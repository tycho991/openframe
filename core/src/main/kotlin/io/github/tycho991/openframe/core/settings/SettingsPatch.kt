package io.github.tycho991.openframe.core.settings

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Ergebnis einer Änderung: neue Einstellungen und die Fehler pro Feld. */
data class PatchResult(
    val settings: Settings,
    val errors: Map<String, String>,
)

/**
 * Wendet eine Teiländerung als JSON auf [Settings] an und prüft jedes Feld.
 * Dieselbe Logik lädt gespeicherte Werte über die Standardwerte, sodass
 * fehlende oder ungültige Werte nach einem Update ihren Standard behalten.
 */
object SettingsPatch {
    fun apply(
        base: Settings,
        patch: JsonObject,
    ): PatchResult {
        val errors = LinkedHashMap<String, String>()
        var s = base
        for ((key, value) in patch) {
            when (key) {
                "name" -> {
                    string(value, key, errors)?.trim()?.let {
                        if (it.isEmpty() || it.length > Settings.NAME_MAX_LENGTH) {
                            errors[key] = "1 bis ${Settings.NAME_MAX_LENGTH} Zeichen"
                        } else {
                            s = s.copy(name = it)
                        }
                    }
                }

                "intervalSeconds" -> {
                    int(value, key, Settings.INTERVAL_SECONDS, errors)?.let { s = s.copy(intervalSeconds = it) }
                }

                "order" -> {
                    enum(value, key, Order.serializer(), errors)?.let { s = s.copy(order = it) }
                }

                "transition" -> {
                    enum(value, key, Transition.serializer(), errors)?.let { s = s.copy(transition = it) }
                }

                "transitionDurationMs" -> {
                    int(value, key, Settings.TRANSITION_DURATION_MS, errors)?.let { s = s.copy(transitionDurationMs = it) }
                }

                "fillMode" -> {
                    enum(value, key, FillMode.serializer(), errors)?.let { s = s.copy(fillMode = it) }
                }

                "orientation" -> {
                    enum(value, key, Orientation.serializer(), errors)?.let { s = s.copy(orientation = it) }
                }

                "clock" -> {
                    s = s.copy(clock = applyClock(s.clock, value, errors))
                }

                "language" -> {
                    enum(value, key, FrameLanguage.serializer(), errors)?.let { s = s.copy(language = it) }
                }

                "cacheBudgetMb" -> {
                    int(value, key, Settings.CACHE_BUDGET_MB, errors)?.let { s = s.copy(cacheBudgetMb = it) }
                }

                else -> {
                    errors[key] = "Unbekanntes Feld"
                }
            }
        }
        return PatchResult(s, errors)
    }

    private fun applyClock(
        base: ClockSettings,
        value: JsonElement,
        errors: MutableMap<String, String>,
    ): ClockSettings {
        if (value !is JsonObject) {
            errors["clock"] = "Erwartet ein Objekt"
            return base
        }
        var c = base
        for ((key, v) in value) {
            val path = "clock.$key"
            when (key) {
                "enabled" -> bool(v, path, errors)?.let { c = c.copy(enabled = it) }
                "position" -> enum(v, path, ClockPosition.serializer(), errors)?.let { c = c.copy(position = it) }
                "sizePercent" -> int(v, path, Settings.CLOCK_SIZE_PERCENT, errors)?.let { c = c.copy(sizePercent = it) }
                "format" -> enum(v, path, ClockFormat.serializer(), errors)?.let { c = c.copy(format = it) }
                else -> errors[path] = "Unbekanntes Feld"
            }
        }
        return c
    }

    private fun string(
        value: JsonElement,
        key: String,
        errors: MutableMap<String, String>,
    ): String? {
        if (value is JsonPrimitive && value.isString) return value.content
        errors[key] = "Erwartet einen Text"
        return null
    }

    private fun bool(
        value: JsonElement,
        key: String,
        errors: MutableMap<String, String>,
    ): Boolean? {
        val b = (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        if (b == null) errors[key] = "Erwartet true oder false"
        return b
    }

    private fun int(
        value: JsonElement,
        key: String,
        range: IntRange,
        errors: MutableMap<String, String>,
    ): Int? {
        val n = (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (n == null || n !in range) {
            errors[key] = "Erwartet eine ganze Zahl von ${range.first} bis ${range.last}"
            return null
        }
        return n
    }

    private fun <E : Enum<E>> enum(
        value: JsonElement,
        key: String,
        serializer: KSerializer<E>,
        errors: MutableMap<String, String>,
    ): E? {
        if (value is JsonPrimitive && value.isString) {
            runCatching { Json.decodeFromJsonElement(serializer, value) }.getOrNull()?.let { return it }
        }
        val names = (0 until serializer.descriptor.elementsCount).map { serializer.descriptor.getElementName(it) }
        errors[key] = "Erlaubt: ${names.joinToString()}"
        return null
    }
}
