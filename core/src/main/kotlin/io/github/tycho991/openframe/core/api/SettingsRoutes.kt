package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.http.HttpResponse
import io.github.tycho991.openframe.core.http.Router
import io.github.tycho991.openframe.core.settings.Settings
import io.github.tycho991.openframe.core.settings.SettingsRepository

/** `GET`/`PATCH /api/v1/settings`. */
fun Router.settingsRoutes(settings: SettingsRepository) {
    get("/api/v1/settings") {
        HttpResponse.json(Settings.serializer(), settings.settings.value)
    }
    patch("/api/v1/settings") { req ->
        val patch = req.bodyObject()
        HttpResponse.json(Settings.serializer(), settings.patch(patch))
    }
}
