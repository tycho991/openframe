package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.causes
import io.github.tycho991.openframe.core.platform.Clock
import io.github.tycho991.openframe.core.sources.SourceException
import io.github.tycho991.openframe.core.sources.SourceLimits
import io.github.tycho991.openframe.core.sources.SyncSchedule
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * OkHttp with the trust of [TrustAnchors] and timeouts that do not leave a sync hanging on a dead server. Redirects
 * are followed here, not by OkHttp: only within the same host, never from `https` back to `http`, so that the
 * `Authorization` header cannot reach another server.
 */
class NextcloudHttp(
    private val client: OkHttpClient = defaultClient(),
    /** For the limits of the bodies and for `Retry-After` as a date. */
    val clock: Clock = Clock.System,
    /** The total time of each kind of call; shorter in a test. */
    val times: TimeLimits = TimeLimits(),
) {
    /**
     * The total time of one call of each kind (design decision 2 of `nextcloud-hardening`): from the request to the
     * last byte of the body, the time until the status line and the headers included.
     */
    data class TimeLimits(
        val listingMs: Long = SourceLimits.LISTING_MS,
        val downloadMs: Long = SourceLimits.DOWNLOAD_MS,
        val loginMs: Long = SourceLimits.LOGIN_MS,
    )

    /**
     * Runs [request] and returns the final response. The caller closes it. Throws [SourceException]. The whole call,
     * every redirect and the body included, ends after [totalMs], so that a server that trickles its status line or its
     * headers cannot hold it. A redirect must stay on the host and never leave `https`; [redirectAllowed] can refuse
     * more, as the login flow does.
     */
    fun execute(
        request: Request,
        totalMs: Long = times.loginMs,
        redirectAllowed: ((HttpUrl) -> SourceException?)? = null,
    ): Response {
        var current = request
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalMs)
        repeat(MAX_REDIRECTS + 1) {
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (left <= 0) throw SourceException(SourceException.Kind.UNREACHABLE, "The server ${current.url.host} did not answer in time")
            val call = client.newCall(current)
            // OkHttp's call timeout covers connecting, the status line, the headers and reading the body.
            call.timeout().timeout(left, TimeUnit.MILLISECONDS)
            val response =
                try {
                    call.execute()
                } catch (e: IOException) {
                    throw translate(e, current.url)
                }
            if (response.code !in REDIRECTS) return response
            val target = response.header("Location")?.let { response.request.url.resolve(it) }
            response.close()
            if (target == null) throw SourceException(SourceException.Kind.PROTOCOL, "The server redirects without an address")
            if (target.host != current.url.host || (current.url.isHttps && !target.isHttps)) {
                throw SourceException(SourceException.Kind.PROTOCOL, "The server redirects to another address (${target.host})")
            }
            redirectAllowed?.invoke(target)?.let { throw it }
            current = current.newBuilder().url(target).build()
        }
        throw SourceException(SourceException.Kind.PROTOCOL, "Too many redirects from ${request.url.host}")
    }

    /** Throws for an answer that is not a success; [what] names the thing asked for. */
    fun requireSuccess(
        response: Response,
        what: String,
    ) {
        if (response.isSuccessful) return
        val code = response.code
        response.close()
        if (code == 429) throw throttled(response)
        throw when (code) {
            401, 403 -> SourceException(SourceException.Kind.UNAUTHORIZED, "The server refused the credentials (HTTP $code)")
            404 -> SourceException(SourceException.Kind.NOT_FOUND, "$what was not found (HTTP 404)")
            in 500..599 -> SourceException(SourceException.Kind.SERVER, "The server answered with an error (HTTP $code)")
            else -> SourceException(SourceException.Kind.PROTOCOL, "Unexpected answer of the server (HTTP $code)")
        }
    }

    /** HTTP 429: the server asks to wait, as long as `Retry-After` says (seconds or a date), clamped to 15 minutes to a day. */
    private fun throttled(response: Response): SourceException {
        val header = response.header("Retry-After")?.trim()
        val askedMs =
            header?.toLongOrNull()?.times(1000)
                ?: response.headers.getDate("Retry-After")?.let { it.time - clock.millis() }
        val wait = askedMs?.let(SyncSchedule::throttledMs)
        val text = if (wait != null) "; the frame tries again in ${wait / 60_000} minutes" else ""
        return SourceException(
            SourceException.Kind.THROTTLED,
            "The server asks to wait before the next request (HTTP 429)$text",
            retryAfterMs = wait,
        )
    }

    /**
     * The kind of a failed connection or read. Only a certificate problem is an untrusted certificate; other failures of
     * TLS are a failed handshake.
     */
    fun translate(
        e: IOException,
        url: HttpUrl,
    ): SourceException {
        val host = url.host
        val chain = e.causes().toList()
        return when {
            chain.any { it is CertificateException || it is SSLPeerUnverifiedException } -> {
                SourceException(SourceException.Kind.TLS, "The certificate of $host is not trusted", e)
            }

            chain.any { it is SSLException } -> {
                SourceException(SourceException.Kind.TLS_HANDSHAKE, "No secure connection to $host could be set up", e)
            }

            chain.any { it is UnknownServiceException } -> {
                // Android refuses plain http when the app does not allow it; the manifest of the frame does.
                SourceException(SourceException.Kind.UNREACHABLE, "The device refuses an unencrypted connection to $host", e)
            }

            e is UnknownHostException -> {
                SourceException(SourceException.Kind.UNREACHABLE, "The server $host was not found", e)
            }

            e is java.io.InterruptedIOException -> {
                SourceException(SourceException.Kind.UNREACHABLE, "The server $host did not answer in time", e)
            }

            else -> {
                SourceException(SourceException.Kind.UNREACHABLE, "The server $host is not reachable", e)
            }
        }
    }

    /** The kind of a failure while reading a body: a limit of [LimitedResponseStream], or a connection that broke off. */
    fun bodyFailure(
        e: Throwable,
        url: HttpUrl,
    ): SourceException {
        (e as? SourceException)?.let { return it }
        val limited = LimitedResponseStream.failureIn(e)
        return when (limited) {
            is LimitedResponseStream.LimitExceeded -> {
                SourceException(SourceException.Kind.LIMIT, limited.message.orEmpty(), e)
            }

            is LimitedResponseStream.NoRoom -> {
                SourceException(SourceException.Kind.STORAGE, limited.message.orEmpty(), e)
            }

            is LimitedResponseStream.TooSlow -> {
                SourceException(SourceException.Kind.UNREACHABLE, limited.message.orEmpty(), e)
            }

            is LimitedResponseStream.Cancelled -> {
                SourceException(SourceException.Kind.UNREACHABLE, limited.message.orEmpty(), e)
            }

            else -> {
                val io = e.causes().firstOrNull { it is IOException } as IOException?
                if (io != null) {
                    translate(io, url)
                } else {
                    SourceException(SourceException.Kind.PROTOCOL, "The answer of ${url.host} could not be read", e)
                }
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
