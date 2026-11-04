package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.http.HttpException
import io.github.tycho991.openframe.core.http.HttpResponse
import io.github.tycho991.openframe.core.http.Router
import kotlinx.serialization.Serializable

@Serializable
class EmptyDto

/** The 503 of the image, upload and sync routes in safe mode (spec `kiosk-host`, "Safe mode after repeated crashes"). */
fun safeModeRefusal() = HttpException(503, "safe_mode", "The frame is in safe mode after repeated crashes; photos rest")

/**
 * `POST /api/v1/safe-mode/exit` (spec `kiosk-host`, "Leaving safe mode"): [leaveSafeMode] restarts the app in normal
 * mode about a second after the answer and returns `false` outside safe mode.
 */
fun Router.healthRoutes(leaveSafeMode: (String) -> Boolean) {
    post("/api/v1/safe-mode/exit") {
        if (!leaveSafeMode("api")) throw HttpException(409, "not_in_safe_mode", "The frame is not in safe mode")
        HttpResponse.json(EmptyDto.serializer(), EmptyDto(), 202)
    }
}
