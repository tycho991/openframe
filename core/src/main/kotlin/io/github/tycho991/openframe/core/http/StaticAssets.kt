package io.github.tycho991.openframe.core.http

import io.github.tycho991.openframe.core.platform.AssetSource

/**
 * Serves the web UI from [assets]. Paths without a file extension, for example
 * `/photos`, are routes of the single-page app and get `index.html`.
 */
class StaticAssets(
    private val assets: AssetSource,
) {
    suspend fun handle(request: HttpRequest): HttpResponse {
        if (request.method != Method.GET && request.method != Method.HEAD) {
            throw HttpException(405, "method_not_allowed", "GET only", headers = mapOf("Allow" to "GET"))
        }
        val path = request.path.trimStart('/')
        if (path.split('/').any { it == ".." || it == "." }) throw HttpException.notFound()
        val lastSegment = path.substringAfterLast('/')
        val isFile = '.' in lastSegment
        val target = if (path.isEmpty() || !isFile) INDEX else path
        val stream = assets.open(target) ?: throw HttpException.notFound()
        stream.close()
        val headers =
            if (target == INDEX) {
                mapOf("Cache-Control" to "no-cache", "Content-Security-Policy" to CSP)
            } else if (target.startsWith("assets/")) {
                // Vite puts a hash into the file name of everything under assets/.
                mapOf("Cache-Control" to "public, max-age=31536000, immutable")
            } else {
                mapOf("Cache-Control" to "no-cache")
            }
        return HttpResponse.StreamBody(200, mimeType(target), { assets.open(target)!! }, null, headers)
    }

    companion object {
        const val INDEX = "index.html"

        const val CSP =
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; " +
                "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"

        private val TYPES =
            mapOf(
                "html" to "text/html; charset=utf-8",
                "js" to "text/javascript; charset=utf-8",
                "mjs" to "text/javascript; charset=utf-8",
                "css" to "text/css; charset=utf-8",
                "json" to "application/json; charset=utf-8",
                "svg" to "image/svg+xml",
                "png" to "image/png",
                "jpg" to "image/jpeg",
                "webp" to "image/webp",
                "ico" to "image/x-icon",
                "woff2" to "font/woff2",
                "webmanifest" to "application/manifest+json",
                "txt" to "text/plain; charset=utf-8",
            )

        fun mimeType(path: String): String = TYPES[path.substringAfterLast('.').lowercase()] ?: "application/octet-stream"
    }
}
