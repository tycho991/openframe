package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.http.HttpException
import io.github.tycho991.openframe.core.http.HttpResponse
import io.github.tycho991.openframe.core.http.Router
import io.github.tycho991.openframe.core.nextcloud.NextcloudConfig
import io.github.tycho991.openframe.core.nextcloud.NextcloudService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Instant

@Serializable
data class NextcloudSettingsDto(
    val enabled: Boolean,
    val folder: String,
    val includeSubfolders: Boolean,
    val removeVanished: Boolean,
    val intervalMinutes: Int,
    val originalsBudgetMb: Int,
)

@Serializable
data class NextcloudStatusDto(
    val syncing: Boolean,
    val lastSyncAt: String?,
    val ok: Boolean?,
    val error: String?,
    val added: Int,
    val updated: Int,
    val removed: Int,
)

@Serializable
data class NextcloudStateDto(
    val connected: Boolean,
    val serverUrl: String?,
    val user: String?,
    val settings: NextcloudSettingsDto,
    val status: NextcloudStatusDto,
)

@Serializable
data class NextcloudLoginRequestDto(
    val serverUrl: String,
)

@Serializable
data class NextcloudLoginDto(
    val flowId: String,
    val loginUrl: String,
    val expiresAt: String,
)

@Serializable
data class NextcloudLoginStateDto(
    val state: String,
    val user: String?,
    val error: String?,
)

@Serializable
data class NextcloudSyncRequestDto(
    val started: Boolean,
)

/** `/api/v1/nextcloud`: settings, status, the login, a sync on request and disconnecting. All of it needs a token. */
fun Router.nextcloudRoutes(service: NextcloudService) {
    fun state(config: NextcloudConfig = service.configs.view()): NextcloudStateDto {
        val status = service.status.value
        val last = status.last
        return NextcloudStateDto(
            connected = config.connected,
            serverUrl = config.serverUrl,
            user = config.user,
            settings =
                NextcloudSettingsDto(
                    config.enabled,
                    config.folder,
                    config.includeSubfolders,
                    config.removeVanished,
                    config.intervalMinutes,
                    config.originalsBudgetMb,
                ),
            status =
                NextcloudStatusDto(
                    syncing = status.syncing,
                    lastSyncAt = last.lastSyncAtMs?.let { Instant.ofEpochMilli(it).toString() },
                    ok = last.lastSyncOk,
                    error = last.error,
                    added = last.added,
                    updated = last.updated,
                    removed = last.removed,
                ),
        )
    }

    get("/api/v1/nextcloud") { HttpResponse.json(NextcloudStateDto.serializer(), state()) }

    patch("/api/v1/nextcloud") { req ->
        val patch = req.bodyJson() as? JsonObject ?: throw HttpException.badRequest("Expected a JSON object")
        HttpResponse.json(NextcloudStateDto.serializer(), state(service.patch(patch)))
    }

    delete("/api/v1/nextcloud") {
        service.disconnect()
        HttpResponse.noContent()
    }

    post("/api/v1/nextcloud/login") { req ->
        val body = req.bodyAs(NextcloudLoginRequestDto.serializer())
        val started = service.startLogin(body.serverUrl)
        HttpResponse.json(
            NextcloudLoginDto.serializer(),
            NextcloudLoginDto(started.flowId, started.loginUrl, started.expiresAt.toString()),
            201,
        )
    }

    get("/api/v1/nextcloud/login/{flowId}") { req ->
        val polled = service.pollLogin(req.pathParam("flowId"))
        HttpResponse.json(NextcloudLoginStateDto.serializer(), NextcloudLoginStateDto(polled.state, polled.user, polled.error))
    }

    post("/api/v1/nextcloud/sync") {
        HttpResponse.json(NextcloudSyncRequestDto.serializer(), NextcloudSyncRequestDto(service.requestSync()), 202)
    }
}
