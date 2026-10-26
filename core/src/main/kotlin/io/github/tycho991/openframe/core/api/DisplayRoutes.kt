package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.display.DisplayController
import io.github.tycho991.openframe.core.display.DisplayState
import io.github.tycho991.openframe.core.http.HttpException
import io.github.tycho991.openframe.core.http.HttpResponse
import io.github.tycho991.openframe.core.http.Router
import io.github.tycho991.openframe.core.settings.SettingsPatch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class DisplayStateDto(
    val available: Boolean,
    val on: Boolean,
    val cause: String,
    val nextChange: String?,
)

private fun DisplayState.toDto() = DisplayStateDto(available, on, cause.api, nextChange?.toString())

/** `GET`/`PUT /api/v1/display`: the display state and the manual switch (spec `display-schedule`). */
fun Router.displayRoutes(display: DisplayController) {
    get("/api/v1/display") {
        HttpResponse.json(DisplayStateDto.serializer(), display.state.value.toDto())
    }
    put("/api/v1/display") { req ->
        val body = req.bodyJson() as? JsonObject ?: throw HttpException.badRequest("Expected a JSON object")
        val fields = LinkedHashMap<String, String>()
        body.keys.filter { it != "on" }.forEach { fields[it] = "Unknown field" }
        val on = SettingsPatch.strictBoolean(body["on"])
        if (on == null) fields["on"] = "Expected true or false"
        if (on == null || fields.isNotEmpty()) throw HttpException.badRequest("Invalid display switch", fields)
        if (!display.available) {
            throw HttpException(
                409,
                "display_unavailable",
                "Device administration is not active: activate it in the device menu on the frame",
            )
        }
        HttpResponse.json(DisplayStateDto.serializer(), display.setManual(on).toDto())
    }
}
