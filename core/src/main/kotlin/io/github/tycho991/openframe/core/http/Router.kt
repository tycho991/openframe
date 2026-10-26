package io.github.tycho991.openframe.core.http

/** Who may call a route. */
enum class Access {
    /** Without token: info, pairing, web UI. */
    PUBLIC,

    /** Only with a valid token. */
    TOKEN,

    /** With token or with the image signature `t` for exactly this photo. */
    TOKEN_OR_IMAGE_SIGNATURE,
}

typealias Handler = suspend (HttpRequest) -> HttpResponse

class Route(
    val method: Method,
    val pattern: String,
    val access: Access,
    val handler: Handler,
) {
    internal val segments: List<String> = split(pattern)

    internal fun match(pathSegments: List<String>): Map<String, String>? {
        if (pathSegments.size != segments.size) return null
        val params = HashMap<String, String>()
        for ((pattern, actual) in segments.zip(pathSegments)) {
            if (pattern.startsWith('{') && pattern.endsWith('}')) {
                if (actual.isEmpty()) return null
                params[pattern.substring(1, pattern.length - 1)] = actual
            } else if (pattern != actual) {
                return null
            }
        }
        return params
    }

    override fun toString() = "$method $pattern ($access)"

    companion object {
        internal fun split(path: String): List<String> = path.trim('/').split('/').let { if (it == listOf("")) emptyList() else it }
    }
}

sealed interface RouteMatch {
    data class Found(
        val route: Route,
        val params: Map<String, String>,
    ) : RouteMatch

    /** The path exists, but not with this method. */
    data class MethodNotAllowed(
        val allowed: Set<Method>,
    ) : RouteMatch

    data object NotFound : RouteMatch
}

/**
 * Narrow routing layer: path patterns with `{param}` segments, one route per
 * method and pattern. Literal segments take precedence over parameters so that
 * `/photos:batch` does not end up as `/photos/{id}`.
 */
class Router {
    private val routes = mutableListOf<Route>()

    val all: List<Route> get() = routes.toList()

    fun add(
        method: Method,
        pattern: String,
        access: Access = Access.TOKEN,
        handler: Handler,
    ) {
        require(routes.none { it.method == method && it.pattern == pattern }) { "Route defined twice: $method $pattern" }
        routes += Route(method, pattern, access, handler)
    }

    fun get(
        pattern: String,
        access: Access = Access.TOKEN,
        handler: Handler,
    ) = add(Method.GET, pattern, access, handler)

    fun post(
        pattern: String,
        access: Access = Access.TOKEN,
        handler: Handler,
    ) = add(Method.POST, pattern, access, handler)

    fun put(
        pattern: String,
        access: Access = Access.TOKEN,
        handler: Handler,
    ) = add(Method.PUT, pattern, access, handler)

    fun patch(
        pattern: String,
        access: Access = Access.TOKEN,
        handler: Handler,
    ) = add(Method.PATCH, pattern, access, handler)

    fun delete(
        pattern: String,
        access: Access = Access.TOKEN,
        handler: Handler,
    ) = add(Method.DELETE, pattern, access, handler)

    fun match(
        method: Method,
        path: String,
    ): RouteMatch {
        val segments = Route.split(path)
        val candidates =
            routes
                .mapNotNull { route -> route.match(segments)?.let { route to it } }
                .sortedByDescending { (route, _) -> route.segments.count { !it.startsWith('{') } }
        if (candidates.isEmpty()) return RouteMatch.NotFound
        val best = candidates.first().first.segments
        val sameShape = candidates.filter { it.first.segments == best }
        val hit = sameShape.firstOrNull { it.first.method == method } ?: candidates.firstOrNull { it.first.method == method }
        return if (hit != null) {
            RouteMatch.Found(hit.first, hit.second)
        } else {
            RouteMatch.MethodNotAllowed(candidates.map { it.first.method }.toSet())
        }
    }
}
