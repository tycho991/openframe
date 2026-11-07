package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.http.HttpException
import io.github.tycho991.openframe.core.http.HttpResponse
import io.github.tycho991.openframe.core.http.Router
import io.github.tycho991.openframe.core.slideshow.SlideState
import io.github.tycho991.openframe.core.slideshow.SlideshowAction
import io.github.tycho991.openframe.core.slideshow.SlideshowController
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class SlideshowStateDto(
    val photo: PhotoDto?,
    val paused: Boolean,
    val resting: Boolean,
)

/** How long `POST /slideshow` waits for the command; a first render on a cold cache may take longer. */
private const val CONTROL_WAIT_MS = 2_000L

/**
 * `GET`/`POST /api/v1/slideshow`: the photo on screen, and next, previous, pause and resume from afar (spec
 * `slideshow-display`). In safe mode no slideshow runs, so both answer 503 `safe_mode` before touching it.
 */
fun Router.slideshowRoutes(
    slideshow: SlideshowController,
    photos: PhotoDtos,
    safeMode: () -> Boolean,
) {
    fun state(): HttpResponse {
        val photo = (slideshow.state.value as? SlideState.Showing)?.photo?.let(photos::current)
        return HttpResponse.json(
            SlideshowStateDto.serializer(),
            SlideshowStateDto(photo, slideshow.paused.value, slideshow.resting.value),
        )
    }

    get("/api/v1/slideshow") {
        if (safeMode()) throw safeModeRefusal()
        state()
    }
    post("/api/v1/slideshow") { req ->
        if (safeMode()) throw safeModeRefusal()
        val body = req.bodyJson() as? JsonObject ?: throw HttpException.badRequest("Expected a JSON object")
        val fields = LinkedHashMap<String, String>()
        body.keys.filter { it != "action" }.forEach { fields[it] = "Unknown field" }
        val name = (body["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val action = SlideshowAction.entries.firstOrNull { it.apiName == name }
        if (action == null) fields["action"] = if ("action" in body) "Expected next, previous, pause or resume" else "Required"
        if (action == null || fields.isNotEmpty()) throw HttpException.badRequest("Invalid slideshow command", fields)
        val step = action == SlideshowAction.NEXT || action == SlideshowAction.PREVIOUS
        // The controller ignores a step while resting as well, for the race where the display goes off in between.
        if (step && slideshow.resting.value) {
            throw HttpException(409, "display_off", "The display is off: the photo changes only when it is on again")
        }
        withTimeoutOrNull(CONTROL_WAIT_MS) { slideshow.control(action) }
        state()
    }
}
