package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.display.DisplayController
import io.github.tycho991.openframe.core.display.DisplayState
import io.github.tycho991.openframe.core.http.HttpException
import io.github.tycho991.openframe.core.http.HttpResponse
import io.github.tycho991.openframe.core.http.Router
import io.github.tycho991.openframe.core.settings.BrightnessSettings
import io.github.tycho991.openframe.core.settings.SettingsPatch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

@Serializable
data class BrightnessStateDto(
    val available: Boolean,
    val autoSupported: Boolean,
    val level: Int,
    val auto: Boolean,
    val dimmed: Boolean,
)

@Serializable
data class DisplayStateDto(
    val available: Boolean,
    val on: Boolean,
    val cause: String,
    val nextChange: String?,
    val brightness: BrightnessStateDto,
)

private fun DisplayState.toDto() =
    DisplayStateDto(
        available,
        on,
        cause.api,
        nextChange?.toString(),
        brightness.run { BrightnessStateDto(available, autoSupported, level, auto, dimmed) },
    )

/**
 * `GET`/`PUT /api/v1/display`: the display state, the manual switch (spec `display-schedule`) and the brightness now
 * (spec `display-brightness`). A request is checked completely before it switches or sets anything.
 */
fun Router.displayRoutes(display: DisplayController) {
    get("/api/v1/display") {
        HttpResponse.json(DisplayStateDto.serializer(), display.state.value.toDto())
    }
    put("/api/v1/display") { req ->
        val body = req.bodyJson() as? JsonObject ?: throw HttpException.badRequest("Expected a JSON object")
        val fields = LinkedHashMap<String, String>()
        body.keys.filter { it != "on" && it != "brightness" }.forEach { fields[it] = "Unknown field" }
        val on =
            body["on"]?.let { value ->
                SettingsPatch.strictBoolean(value).also { if (it == null) fields["on"] = "Expected true or false" }
            }
        val level =
            body["brightness"]?.let { value ->
                val n = (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in BrightnessSettings.LEVEL }
                n.also { if (it == null) fields["brightness"] = "Expected an integer from 1 to 100" }
            }
        if (fields.isNotEmpty()) throw HttpException.badRequest("Invalid display switch", fields)
        if (on == null && level == null) throw HttpException.badRequest("Expected `on`, `brightness` or both")
        if (on != null && !display.available) {
            throw HttpException(
                409,
                "display_unavailable",
                "Device administration is not active: activate it in the device menu on the frame",
            )
        }
        if (level != null && !display.brightnessAvailable) {
            throw HttpException(
                409,
                "brightness_unavailable",
                "openframe may not modify system settings: allow it in the device menu on the frame",
            )
        }
        HttpResponse.json(DisplayStateDto.serializer(), display.set(on, level).toDto())
    }
}
