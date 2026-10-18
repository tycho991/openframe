package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.http.HttpException
import io.github.tycho991.openframe.core.http.HttpResponse
import io.github.tycho991.openframe.core.http.Router
import io.github.tycho991.openframe.core.log.AppLog
import kotlinx.serialization.Serializable

@Serializable
data class LogEntryDto(
    val time: String,
    val level: String,
    val tag: String,
    val message: String,
)

@Serializable
data class LogListDto(
    val entries: List<LogEntryDto>,
)

/** `GET /api/v1/logs`: die letzten Einträge des Protokolls. */
fun Router.diagnosticsRoutes(log: AppLog) {
    get("/api/v1/logs") { req ->
        val limit =
            req.queryParam("limit")?.let {
                it.toIntOrNull()?.takeIf { n -> n in 1..5000 }
                    ?: throw HttpException.badRequest("limit 1 bis 5000")
            }
                ?: 500
        val entries = log.recent(limit).map { LogEntryDto(it.time.toString(), it.level.name.lowercase(), it.tag, it.message) }
        HttpResponse.json(LogListDto.serializer(), LogListDto(entries))
    }
}
