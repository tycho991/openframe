package io.github.tycho991.openframe.core.http

import io.github.tycho991.openframe.core.readAtMost
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

enum class Method {
    GET,
    POST,
    PUT,
    PATCH,
    DELETE,
    HEAD,
    OPTIONS,
    ;

    val isMutating: Boolean get() = this == POST || this == PUT || this == PATCH || this == DELETE
}

/** JSON configuration of the API: strict when reading, complete when writing. */
val ApiJson =
    Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

/** An incoming request, independent of the transport. */
class HttpRequest(
    val method: Method,
    val path: String,
    val query: Map<String, List<String>>,
    /** Headers with lower-case names. */
    val headers: Map<String, String>,
    val contentLength: Long?,
    private val body: InputStream,
) {
    /** Parameters from the path pattern, set by the router. */
    var pathParams: Map<String, String> = emptyMap()
        internal set

    /** The authenticated client, set by the auth filter. */
    var clientId: String? = null

    fun header(name: String): String? = headers[name.lowercase()]

    fun queryParam(name: String): String? = query[name]?.firstOrNull()

    fun pathParam(name: String): String = pathParams[name] ?: error("Path parameter $name is missing in the route")

    /** The body as a stream, limited to `Content-Length`. May be read only once. */
    fun bodyStream(): InputStream = body

    /** Reads the body as JSON. Rejects bodies over [maxBytes] with 413. */
    fun <T> bodyAs(
        serializer: KSerializer<T>,
        maxBytes: Long = MAX_JSON_BYTES,
    ): T {
        val json = bodyJson(maxBytes)
        return try {
            ApiJson.decodeFromJsonElement(serializer, json)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw HttpException.badRequest("Invalid body: ${e.message?.lineSequence()?.first()}")
        } catch (e: IllegalArgumentException) {
            throw HttpException.badRequest("Invalid body: ${e.message?.lineSequence()?.first()}")
        }
    }

    fun bodyJson(maxBytes: Long = MAX_JSON_BYTES): JsonElement {
        val type = header("content-type")?.substringBefore(';')?.trim()?.lowercase()
        if (type != "application/json") {
            throw HttpException(415, "unsupported_media_type", "Expected application/json")
        }
        val length = contentLength
        if (length != null && length > maxBytes) throw HttpException(413, "too_large", "Body larger than $maxBytes bytes")
        val bytes = body.readAtMost((maxBytes + 1).toInt())
        if (bytes.size > maxBytes) throw HttpException(413, "too_large", "Body larger than $maxBytes bytes")
        return try {
            ApiJson.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8))
        } catch (e: kotlinx.serialization.SerializationException) {
            throw HttpException(400, "invalid_json", "Not valid JSON: ${e.message}")
        }
    }

    companion object {
        const val MAX_JSON_BYTES = 1L * 1024 * 1024

        fun parseQuery(raw: String?): Map<String, List<String>> {
            if (raw.isNullOrEmpty()) return emptyMap()
            return raw
                .split('&')
                .filter { it.isNotEmpty() }
                .map { part ->
                    val key = part.substringBefore('=')
                    val value = if ('=' in part) part.substringAfter('=') else ""
                    decode(key) to decode(value)
                }.groupBy({ it.first }, { it.second })
        }

        private fun decode(s: String): String = URLDecoder.decode(s, "UTF-8")
    }
}

/** A response, independent of the transport. */
sealed class HttpResponse {
    abstract val status: Int
    abstract val headers: Map<String, String>

    abstract fun withHeaders(extra: Map<String, String>): HttpResponse

    data class Json(
        override val status: Int,
        val body: String,
        override val headers: Map<String, String> = emptyMap(),
    ) : HttpResponse() {
        override fun withHeaders(extra: Map<String, String>) = copy(headers = headers + extra)
    }

    data class Empty(
        override val status: Int,
        override val headers: Map<String, String> = emptyMap(),
    ) : HttpResponse() {
        override fun withHeaders(extra: Map<String, String>) = copy(headers = headers + extra)
    }

    /** A file, with support for `Range`. */
    data class FileBody(
        override val status: Int,
        val file: File,
        val contentType: String,
        override val headers: Map<String, String> = emptyMap(),
    ) : HttpResponse() {
        override fun withHeaders(extra: Map<String, String>) = copy(headers = headers + extra)
    }

    /** A stream of known or unknown length, e.g. an asset of the web UI. */
    data class StreamBody(
        override val status: Int,
        val contentType: String,
        val open: () -> InputStream,
        val length: Long?,
        override val headers: Map<String, String> = emptyMap(),
    ) : HttpResponse() {
        override fun withHeaders(extra: Map<String, String>) = copy(headers = headers + extra)
    }

    companion object {
        fun <T> json(
            serializer: KSerializer<T>,
            value: T,
            status: Int = 200,
        ): HttpResponse = Json(status, ApiJson.encodeToString(serializer, value))

        fun noContent(): HttpResponse = Empty(204)

        fun error(e: HttpException): HttpResponse {
            val body = ErrorBody(ErrorDetail(e.code, e.message, e.fields.takeIf { it.isNotEmpty() }))
            return Json(e.status, ApiJson.encodeToString(ErrorBody.serializer(), body), e.headers)
        }
    }
}

/** Error that ends as a JSON error response with [status]. */
class HttpException(
    val status: Int,
    val code: String,
    override val message: String,
    val fields: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
) : RuntimeException(message) {
    companion object {
        fun badRequest(
            message: String,
            fields: Map<String, String> = emptyMap(),
        ) = HttpException(400, if (fields.isEmpty()) "bad_request" else "validation_failed", message, fields)

        fun notFound(message: String = "Not found") = HttpException(404, "not_found", message)

        fun unauthorized(message: String = "No valid token") = HttpException(401, "unauthorized", message)
    }
}

@Serializable
data class ErrorBody(
    val error: ErrorDetail,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ErrorDetail(
    val code: String,
    val message: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val fields: Map<String, String>? = null,
)
