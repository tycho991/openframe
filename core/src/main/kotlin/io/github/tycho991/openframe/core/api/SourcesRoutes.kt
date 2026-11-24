package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.ValidationException
import io.github.tycho991.openframe.core.http.HttpException
import io.github.tycho991.openframe.core.http.HttpResponse
import io.github.tycho991.openframe.core.http.Router
import io.github.tycho991.openframe.core.nextcloud.NextcloudConnector
import io.github.tycho991.openframe.core.nextcloud.NextcloudShareKind
import io.github.tycho991.openframe.core.sources.Connection
import io.github.tycho991.openframe.core.sources.SourceErrorCodes
import io.github.tycho991.openframe.core.sources.SourceRegistry
import io.github.tycho991.openframe.core.sources.StoredSource
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class SourceSettingsDto(
    val name: String,
    val enabled: Boolean,
    val folder: String,
    val includeSubfolders: Boolean,
    val removeVanished: Boolean,
    val intervalMinutes: Int,
    val originalsBudgetMb: Int,
)

@Serializable
data class SourceStatusDto(
    val syncing: Boolean,
    val waiting: Boolean,
    val lastSyncAt: String?,
    val ok: Boolean?,
    val error: String?,
    /** A value of the contract's `SourceErrorCode`, `null` for a success, a result of an older version or an unknown code. */
    val errorCode: String?,
    /** The state of rejected access: the next try is at most once a day unless the user acts. */
    val rejected: Boolean,
    val added: Int,
    val updated: Int,
    val removed: Int,
)

@Serializable
data class SourceDto(
    val id: String,
    val kind: String,
    val serverUrl: String,
    val user: String?,
    val settings: SourceSettingsDto,
    val status: SourceStatusDto,
)

@Serializable
data class SourceListDto(
    val sources: List<SourceDto>,
)

@Serializable
data class ShareRequestDto(
    val kind: String,
    val link: String,
    val password: String? = null,
    val name: String? = null,
)

@Serializable
data class SourceLoginRequestDto(
    /** Ignored with [confirmSourceId]. */
    val serverUrl: String? = null,
    /** An account source to confirm by the login (design decision 16 of `paired-device-guards`). */
    val confirmSourceId: String? = null,
)

@Serializable
data class SourceLoginDto(
    val flowId: String,
    val loginUrl: String,
    val expiresAt: String,
)

@Serializable
data class SourceLoginStateDto(
    val state: String,
    val user: String?,
    val sourceId: String?,
    val error: String?,
    /** For `failed`: a value of the contract's `SourceErrorCode`. */
    val errorCode: String?,
)

@Serializable
data class SyncRequestDto(
    val started: Boolean,
)

/**
 * `/api/v1/sources`: the remote sources with settings and status, adding a share, the login flow for an account, and
 * per source changes, a sync on request and removal. All of it needs a token. No response carries a password, an app
 * password or a share token: [SourceDto] takes only the server and the login name from the connection.
 */
fun Router.sourcesRoutes(
    sources: SourceRegistry,
    nextcloud: NextcloudConnector,
    /** Safe mode: a sync on request answers 503 `safe_mode`. */
    safeMode: () -> Boolean = { false },
) {
    fun dto(config: StoredSource): SourceDto {
        val status = sources.status(config.id)
        val last = status.last
        return SourceDto(
            id = config.id,
            kind = config.kind,
            serverUrl = config.connection.serverUrl,
            user = (config.connection as? Connection.Account)?.loginName,
            settings =
                SourceSettingsDto(
                    config.name,
                    config.enabled,
                    config.folder,
                    config.includeSubfolders,
                    config.removeVanished,
                    config.intervalMinutes,
                    config.originalsBudgetMb,
                ),
            status =
                SourceStatusDto(
                    syncing = status.syncing,
                    waiting = status.waiting,
                    lastSyncAt = last.lastSyncAtMs?.let { Instant.ofEpochMilli(it).toString() },
                    ok = last.lastSyncOk,
                    error = last.error,
                    errorCode = if (last.lastSyncOk == false) SourceErrorCodes.served(last.errorCode) else null,
                    rejected = last.lastSyncOk == false && last.rejected && !last.credentialsReplaced,
                    added = last.added,
                    updated = last.updated,
                    removed = last.removed,
                ),
        )
    }

    fun known(id: String) = sources.configs.get(id) ?: throw HttpException.notFound("No such source")

    get("/api/v1/sources") {
        HttpResponse.json(
            SourceListDto.serializer(),
            SourceListDto(
                sources.configs.sources.value
                    .map(::dto),
            ),
        )
    }

    post("/api/v1/sources") { req ->
        val body = req.bodyAs(ShareRequestDto.serializer())
        if (body.kind != NextcloudShareKind.ID) {
            throw ValidationException(mapOf("kind" to "Only ${NextcloudShareKind.ID}; an account is added with the login flow"))
        }
        HttpResponse.json(SourceDto.serializer(), dto(nextcloud.addShare(body.link, body.password, body.name)), 201)
    }

    post("/api/v1/sources/login") { req ->
        val body = req.bodyAs(SourceLoginRequestDto.serializer())
        val started = nextcloud.startLogin(body.serverUrl, req.clientId!!, body.confirmSourceId)
        HttpResponse.json(
            SourceLoginDto.serializer(),
            SourceLoginDto(started.flowId, started.loginUrl, started.expiresAt.toString()),
            201,
        )
    }

    get("/api/v1/sources/login/{flowId}") { req ->
        val polled = nextcloud.pollLogin(req.pathParam("flowId"), req.clientId!!)
        HttpResponse.json(
            SourceLoginStateDto.serializer(),
            SourceLoginStateDto(polled.state, polled.user, polled.sourceId, polled.error, SourceErrorCodes.served(polled.code)),
        )
    }

    get("/api/v1/sources/{sourceId}") { req ->
        HttpResponse.json(SourceDto.serializer(), dto(known(req.pathParam("sourceId"))))
    }

    patch("/api/v1/sources/{sourceId}") { req ->
        val id = req.pathParam("sourceId")
        val patch = req.bodyObject()
        HttpResponse.json(SourceDto.serializer(), dto(sources.patch(id, patch, req.clientId)))
    }

    delete("/api/v1/sources/{sourceId}") { req ->
        if (!sources.remove(req.pathParam("sourceId"))) throw HttpException.notFound("No such source")
        HttpResponse.noContent()
    }

    post("/api/v1/sources/{sourceId}/sync") { req ->
        if (safeMode()) throw safeModeRefusal()
        HttpResponse.json(SyncRequestDto.serializer(), SyncRequestDto(sources.requestSync(req.pathParam("sourceId"))), 202)
    }
}
