package io.github.tycho991.openframe.core.nextcloud

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * OkHttp with the trust of [TrustAnchors] and timeouts that do not leave a sync hanging on a dead server. Redirects
 * are followed here, not by OkHttp: only within the same host, never from `https` back to `http`, so that the
 * `Authorization` header cannot reach another server.
 */
class NextcloudHttp(
    private val client: OkHttpClient = defaultClient(),
) {
    /** Runs [request] and returns the final response. The caller closes it. Throws [NextcloudException]. */
    fun execute(request: Request): Response {
        var current = request
        repeat(MAX_REDIRECTS + 1) {
            val response =
                try {
                    client.newCall(current).execute()
                } catch (e: IOException) {
                    throw translate(e, current.url)
                }
            if (response.code !in REDIRECTS) return response
            val target = response.header("Location")?.let { response.request.url.resolve(it) }
            response.close()
            if (target == null) throw NextcloudException(NextcloudException.Kind.PROTOCOL, "The server redirects without an address")
            if (target.host != current.url.host || (current.url.isHttps && !target.isHttps)) {
                throw NextcloudException(NextcloudException.Kind.PROTOCOL, "The server redirects to another address (${target.host})")
            }
            current = current.newBuilder().url(target).build()
        }
        throw NextcloudException(NextcloudException.Kind.PROTOCOL, "Too many redirects from ${request.url.host}")
    }

    /** Throws for an answer that is not a success; [what] names the thing asked for. */
    fun requireSuccess(
        response: Response,
        what: String,
    ) {
        if (response.isSuccessful) return
        val code = response.code
        response.close()
        throw when (code) {
            401, 403 -> NextcloudException(NextcloudException.Kind.UNAUTHORIZED, "The server refused the credentials (HTTP $code)")
            404 -> NextcloudException(NextcloudException.Kind.NOT_FOUND, "$what was not found (HTTP 404)")
            in 500..599 -> NextcloudException(NextcloudException.Kind.SERVER, "The server answered with an error (HTTP $code)")
            else -> NextcloudException(NextcloudException.Kind.PROTOCOL, "Unexpected answer of the server (HTTP $code)")
        }
    }

    private fun translate(
        e: IOException,
        url: HttpUrl,
    ): NextcloudException {
        val host = url.host
        return when {
            e is SSLException || e.cause is CertificateException -> {
                NextcloudException(NextcloudException.Kind.TLS, "The certificate of $host is not trusted", e)
            }

            e is UnknownHostException -> {
                NextcloudException(NextcloudException.Kind.UNREACHABLE, "The server $host was not found", e)
            }

            e is SocketTimeoutException -> {
                NextcloudException(NextcloudException.Kind.UNREACHABLE, "The server $host did not answer in time", e)
            }

            else -> {
                NextcloudException(NextcloudException.Kind.UNREACHABLE, "The server $host is not reachable", e)
            }
        }
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        const val CONNECT_TIMEOUT_S = 15L
        const val READ_TIMEOUT_S = 60L

        fun defaultClient(
            trust: javax.net.ssl.X509TrustManager = TrustAnchors.trustManager(),
            connectTimeoutSeconds: Long = CONNECT_TIMEOUT_S,
            readTimeoutSeconds: Long = READ_TIMEOUT_S,
        ): OkHttpClient =
            OkHttpClient
                .Builder()
                .sslSocketFactory(TrustAnchors.socketFactory(trust), trust)
                .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
    }
}
