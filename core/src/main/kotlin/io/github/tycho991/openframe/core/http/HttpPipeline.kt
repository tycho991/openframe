package io.github.tycho991.openframe.core.http

import io.github.tycho991.openframe.core.ValidationException
import io.github.tycho991.openframe.core.log.AppLog
import kotlinx.coroutines.CancellationException
import java.net.URI

/** Checks tokens and image signatures. Implemented in `auth`. */
interface Authenticator {
    /** Returns the ID of the client for the request's bearer token or `null`. */
    fun clientFor(request: HttpRequest): String?

    /** Whether the request carries a valid image signature for the photo [photoId]. */
    fun hasValidImageSignature(
        request: HttpRequest,
        photoId: String,
    ): Boolean
}

/**
 * Processes every request in the same order: find route,
 * check origin, check access, run handler, map errors and
 * set security headers. Paths outside `/api/` go to [fallback],
 * the serving of the web UI.
 */
class HttpPipeline(
    private val router: Router,
    private val authenticator: Authenticator,
    private val log: AppLog,
    private val fallback: Handler = { throw HttpException.notFound() },
) {
    suspend fun handle(request: HttpRequest): HttpResponse {
        val response =
            try {
                dispatch(request)
            } catch (e: HttpException) {
                HttpResponse.error(e)
            } catch (e: ValidationException) {
                HttpResponse.error(HttpException.badRequest("Invalid values", e.fields))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.e(TAG, "Error on ${request.method} ${request.path}", e)
                HttpResponse.error(HttpException(500, "internal_error", "Internal error"))
            }
        return response.withHeaders(SECURITY_HEADERS)
    }

    private suspend fun dispatch(request: HttpRequest): HttpResponse {
        if (request.method.isMutating) checkOrigin(request)
        if (!request.path.startsWith(API_PREFIX)) return fallback(request)
        return when (val match = router.match(request.method, request.path)) {
            is RouteMatch.NotFound -> {
                throw HttpException.notFound("Unknown endpoint ${request.path}")
            }

            is RouteMatch.MethodNotAllowed -> {
                throw HttpException(
                    405,
                    "method_not_allowed",
                    "Method ${request.method} not allowed",
                    headers = mapOf("Allow" to match.allowed.joinToString(", ")),
                )
            }

            is RouteMatch.Found -> {
                request.pathParams = match.params
                authorize(request, match.route)
                match.route.handler(request)
            }
        }
    }

    private fun authorize(
        request: HttpRequest,
        route: Route,
    ) {
        if (route.access == Access.PUBLIC) {
            request.clientId = authenticator.clientFor(request)
            return
        }
        val client = authenticator.clientFor(request)
        if (client != null) {
            request.clientId = client
            return
        }
        if (route.access == Access.TOKEN_OR_IMAGE_SIGNATURE) {
            val photoId = request.pathParams["photoId"]
            if (photoId != null && authenticator.hasValidImageSignature(request, photoId)) return
            throw HttpException.unauthorized("No valid token and no valid signature")
        }
        throw HttpException.unauthorized()
    }

    /**
     * Mutating requests from the browser may only come from the own origin.
     * Clients without these headers (curl, Home Assistant) are not affected.
     */
    private fun checkOrigin(request: HttpRequest) {
        val fetchSite = request.header("sec-fetch-site")
        if (fetchSite != null && fetchSite != "same-origin" && fetchSite != "none") {
            throw HttpException(403, "forbidden_origin", "Request from a foreign page rejected")
        }
        val origin = request.header("origin") ?: return
        val host = request.header("host")
        val originHost = runCatching { URI(origin).let { u -> if (u.port == -1) u.host else "${u.host}:${u.port}" } }.getOrNull()
        if (originHost == null || host == null || !originHost.equals(host, ignoreCase = true)) {
            throw HttpException(403, "forbidden_origin", "Request from a foreign origin rejected")
        }
    }

    companion object {
        const val API_PREFIX = "/api/"
        private const val TAG = "http"

        val SECURITY_HEADERS =
            mapOf(
                "X-Content-Type-Options" to "nosniff",
                "X-Frame-Options" to "DENY",
                "Referrer-Policy" to "no-referrer",
            )
    }
}
