package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.auth.ClientRegistry
import io.github.tycho991.openframe.core.http.HttpException
import io.github.tycho991.openframe.core.http.HttpResponse
import io.github.tycho991.openframe.core.http.Router
import io.github.tycho991.openframe.core.update.InstallRequest
import io.github.tycho991.openframe.core.update.UpdateManager
import io.github.tycho991.openframe.core.update.UpdateState
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class ReleaseDto(
    val version: String,
    val notes: String,
    val publishedAt: String?,
)

@Serializable
data class UpdateCheckDto(
    val at: String,
    val result: String,
)

@Serializable
data class UpdateErrorDto(
    val code: String,
    val message: String,
)

@Serializable
data class UpdateStateDto(
    val installedVersion: String,
    val phase: String,
    val latest: ReleaseDto?,
    val lastCheck: UpdateCheckDto?,
    val error: UpdateErrorDto?,
    val installAllowed: Boolean,
)

private fun UpdateState.toDto() =
    UpdateStateDto(
        installedVersion,
        phase.api,
        latest?.let { ReleaseDto(it.version ?: it.tag.removePrefix("v"), it.notes, it.publishedAt) },
        lastCheck?.let { UpdateCheckDto(Instant.ofEpochMilli(it.atMs).toString(), it.result.api) },
        error?.let { UpdateErrorDto(it.code.api, it.message) },
        installAllowed,
    )

/**
 * `GET /api/v1/update`, `POST /api/v1/update/check` and `POST /api/v1/update/install` (spec `app-update`, design
 * decision 11 of `app-auto-update`). A refused installation answers 409 with a code of `InstallRefusal`.
 */
fun Router.updateRoutes(
    update: UpdateManager,
    clients: ClientRegistry,
) {
    get("/api/v1/update") {
        HttpResponse.json(UpdateStateDto.serializer(), update.current().toDto())
    }
    post("/api/v1/update/check") {
        HttpResponse.json(UpdateStateDto.serializer(), update.check().toDto(), 202)
    }
    post("/api/v1/update/install") { req ->
        val client =
            clients.clients.value
                .firstOrNull { it.id == req.clientId }
                ?.let { "\"${it.name}\"" } ?: "client ${req.clientId}"
        when (val answer = update.install(client)) {
            InstallRequest.Accepted -> HttpResponse.json(UpdateStateDto.serializer(), update.current().toDto(), 202)
            else -> HttpResponse.error(HttpException(409, answer.code!!, answer.message))
        }
    }
}
