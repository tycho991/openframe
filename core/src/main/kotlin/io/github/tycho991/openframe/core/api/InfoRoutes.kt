package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.http.Access
import io.github.tycho991.openframe.core.http.HttpResponse
import io.github.tycho991.openframe.core.http.Router
import io.github.tycho991.openframe.core.identity.FrameIdentity
import io.github.tycho991.openframe.core.platform.PlatformInfo
import io.github.tycho991.openframe.core.settings.SettingsRepository
import kotlinx.serialization.Serializable

@Serializable
data class SizeDto(
    val width: Int,
    val height: Int,
)

@Serializable
data class InfoDto(
    val id: String,
    val name: String,
    val appVersion: String,
    val androidVersion: String,
    val screen: SizeDto,
    val apiVersion: Int = 1,
)

/** `GET /api/v1/info`, retrievable without pairing and without settings. */
fun Router.infoRoutes(
    identity: FrameIdentity,
    settings: SettingsRepository,
    platform: PlatformInfo,
) {
    get("/api/v1/info", Access.PUBLIC) {
        val info =
            InfoDto(
                id = identity.id,
                name = settings.settings.value.name,
                appVersion = platform.appVersion,
                androidVersion = platform.androidVersion,
                screen = SizeDto(platform.screenWidth, platform.screenHeight),
            )
        HttpResponse.json(InfoDto.serializer(), info)
    }
}
