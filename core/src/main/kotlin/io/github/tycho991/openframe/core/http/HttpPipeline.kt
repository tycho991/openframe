package io.github.tycho991.openframe.core.http

import io.github.tycho991.openframe.core.log.AppLog
import kotlinx.coroutines.CancellationException
import java.net.URI

/** Prüft Token und Bild-Signaturen. Implementiert in `auth`. */
interface Authenticator {
    /** Liefert die ID des Clients zum Bearer-Token der Anfrage oder `null`. */
    fun clientFor(request: HttpRequest): String?

    /** Ob die Anfrage eine gültige Bild-Signatur für das Foto [photoId] trägt. */
    fun hasValidImageSignature(
        request: HttpRequest,
        photoId: String,
    ): Boolean
}

/**
 * Verarbeitet jede Anfrage in derselben Reihenfolge: Route finden,
 * Origin prüfen, Zugriff prüfen, Handler ausführen, Fehler abbilden und
 * Sicherheitsheader setzen. Pfade außerhalb von `/api/` gehen an [fallback],
 * die Auslieferung der Web-UI.
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.e(TAG, "Fehler bei ${request.method} ${request.path}", e)
                HttpResponse.error(HttpException(500, "internal_error", "Interner Fehler"))
            }
        return response.withHeaders(SECURITY_HEADERS)
    }

    private suspend fun dispatch(request: HttpRequest): HttpResponse {
        if (request.method.isMutating) checkOrigin(request)
        if (!request.path.startsWith(API_PREFIX)) return fallback(request)
        return when (val match = router.match(request.method, request.path)) {
            is RouteMatch.NotFound -> {
                throw HttpException.notFound("Unbekannter Endpunkt ${request.path}")
            }

            is RouteMatch.MethodNotAllowed -> {
                throw HttpException(
                    405,
                    "method_not_allowed",
                    "Methode ${request.method} nicht erlaubt",
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
            throw HttpException.unauthorized("Kein gültiges Token und keine gültige Signatur")
        }
        throw HttpException.unauthorized()
    }

    /**
     * Ändernde Anfragen aus dem Browser dürfen nur vom eigenen Origin kommen.
     * Clients ohne diese Header (curl, Home Assistant) sind nicht betroffen.
     */
    private fun checkOrigin(request: HttpRequest) {
        val fetchSite = request.header("sec-fetch-site")
        if (fetchSite != null && fetchSite != "same-origin" && fetchSite != "none") {
            throw HttpException(403, "forbidden_origin", "Anfrage von fremder Seite abgelehnt")
        }
        val origin = request.header("origin") ?: return
        val host = request.header("host")
        val originHost = runCatching { URI(origin).let { u -> if (u.port == -1) u.host else "${u.host}:${u.port}" } }.getOrNull()
        if (originHost == null || host == null || !originHost.equals(host, ignoreCase = true)) {
            throw HttpException(403, "forbidden_origin", "Anfrage von fremdem Origin abgelehnt")
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
