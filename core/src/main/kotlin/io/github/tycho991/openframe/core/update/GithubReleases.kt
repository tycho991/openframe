package io.github.tycho991.openframe.core.update

import io.github.tycho991.openframe.core.nextcloud.TrustAnchors
import io.github.tycho991.openframe.core.readAtMost
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.OutputStream
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.X509TrustManager

/** The latest release and the `ETag` of GitHub's answer. */
data class FetchedRelease(
    val release: Release,
    val etag: String?,
)

/** Where the frame finds its updates; [GithubReleases] on the frame, a fake for the test server and the tests. */
interface ReleaseSource {
    /** The latest release, or `null` when GitHub answers `304` for [etag]. Throws [UpdateException]. */
    fun latest(etag: String?): FetchedRelease?

    /** Writes the file at [url] to [out], at most [maxBytes] plus one byte. Throws [UpdateException]. */
    fun download(
        url: String,
        out: OutputStream,
        maxBytes: Long,
    )
}

/**
 * The latest release of openframe on GitHub (design decision 2 of `app-auto-update`), without credentials and only over
 * `https`: the base address, the address of the APK and every redirect hop. A redirect from `https` to `http` is not
 * followed, and a network interceptor refuses any hop that is not `https`, so nothing is ever sent without TLS. Trust
 * comes from the platform and [TrustAnchors.forUpdates], which has extra roots so that a new root of GitHub or its
 * asset host cannot lock out the update that would bring it.
 */
class GithubReleases(
    private val baseUrl: String = "https://api.github.com",
    private val repository: String = "tycho991/openframe",
    /** `null` for the platform and [TrustAnchors.forUpdates]. */
    private val trust: X509TrustManager? = null,
) : ReleaseSource {
    private val untrusted = AtomicReference<String?>(null)

    // On first use: the core is created on Android's main thread, and building the trust store reads certificates.
    private val client by lazy {
        val recording = RecordingTrustManager(trust ?: TrustAnchors.trustManager(TrustAnchors.forUpdates())) { untrusted.set(it) }
        OkHttpClient
            .Builder()
            .sslSocketFactory(TrustAnchors.socketFactory(recording), recording)
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .addNetworkInterceptor { chain ->
                val url = chain.request().url
                if (!url.isHttps) throw NotHttpsException(url.host)
                chain.proceed(chain.request())
            }.build()
    }

    override fun latest(etag: String?): FetchedRelease? {
        val request =
            request("$baseUrl/repos/$repository/releases/latest", "the update address")
                .header("Accept", "application/vnd.github+json")
                .apply { etag?.let { header("If-None-Match", it) } }
                .build()
        return call(request).use { response ->
            when {
                response.code == 304 && etag != null -> {
                    null
                }

                response.code == 200 -> {
                    val body = response.body?.byteStream()?.use { it.readAtMost(MAX_RELEASE_BYTES + 1) } ?: ByteArray(0)
                    if (body.size > MAX_RELEASE_BYTES) throw UpdateException(UpdateErrorCode.UPDATE_FAILED, "GitHub's answer is too large")
                    FetchedRelease(parse(body.toString(Charsets.UTF_8)), response.header("ETag"))
                }

                else -> {
                    throw failure(response)
                }
            }
        }
    }

    override fun download(
        url: String,
        out: OutputStream,
        maxBytes: Long,
    ) {
        val request = request(url, "the address of the APK").header("Accept", "application/octet-stream").build()
        call(request).use { response ->
            if (!response.isSuccessful) throw failure(response)
            try {
                val input = response.body?.byteStream() ?: return
                input.use {
                    val buffer = ByteArray(BUFFER_BYTES)
                    var total = 0L
                    while (total <= maxBytes) {
                        val n = it.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        total += n
                    }
                }
            } catch (e: IOException) {
                throw translate(e, response.request.url.host)
            }
        }
    }

    private fun request(
        url: String,
        what: String,
    ): Request.Builder {
        val parsed = url.toHttpUrlOrNull()
        if (parsed == null || !parsed.isHttps) throw UpdateException(UpdateErrorCode.UPDATE_FAILED, "Refused $what, which is not https")
        // No version: the same User-Agent on every frame, as for the Nextcloud requests.
        return Request.Builder().url(parsed).header("User-Agent", USER_AGENT)
    }

    private fun call(request: Request): Response {
        untrusted.set(null)
        return try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw translate(e, request.url.host)
        }
    }

    private fun failure(response: Response): UpdateException {
        val code = response.code
        val limited = (code == 403 || code == 429) && response.header("x-ratelimit-remaining") == "0"
        return when {
            code == 404 -> {
                UpdateException(UpdateErrorCode.UPDATE_NOT_FOUND, "GitHub has no release of openframe (HTTP 404)")
            }

            limited -> {
                val reset = response.header("x-ratelimit-reset")?.toLongOrNull()?.times(1000)
                UpdateException(UpdateErrorCode.UPDATE_RATE_LIMITED, "GitHub's rate limit is reached", resetAtMs = reset)
            }

            // Not followed by OkHttp: a redirect from https to http.
            response.isRedirect -> {
                UpdateException(
                    UpdateErrorCode.UPDATE_FAILED,
                    "Refused a redirect of ${response.request.url.host} to an address without https",
                )
            }

            else -> {
                UpdateException(UpdateErrorCode.UPDATE_UNREACHABLE, "${response.request.url.host} answered with HTTP $code")
            }
        }
    }

    private fun translate(
        e: IOException,
        host: String,
    ): UpdateException {
        val chain = untrusted.get()
        return when {
            e is NotHttpsException -> {
                UpdateException(UpdateErrorCode.UPDATE_FAILED, "Refused a redirect to ${e.host} without https", cause = e)
            }

            chain != null || e.causes().any { it is CertificateException } -> {
                UpdateException(UpdateErrorCode.UPDATE_TLS, "The certificate of $host is not trusted", chain, cause = e)
            }

            else -> {
                UpdateException(UpdateErrorCode.UPDATE_UNREACHABLE, "$host is not reachable (${e.javaClass.simpleName})", cause = e)
            }
        }
    }

    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause.takeIf { c -> c !== it } }

    /** An `http` hop, refused before anything is sent. */
    private class NotHttpsException(
        val host: String,
    ) : IOException("Not https: $host")

    /** Passes [delegate]'s verdict on and hands a refused chain, subjects and issuers, to [onUntrusted] for the log. */
    private class RecordingTrustManager(
        private val delegate: X509TrustManager,
        private val onUntrusted: (String) -> Unit,
    ) : X509TrustManager {
        override fun checkClientTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ) = delegate.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ) {
            try {
                delegate.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                onUntrusted(chain.joinToString("; ") { "subject ${it.subjectX500Principal.name}, issuer ${it.issuerX500Principal.name}" })
                throw e
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    }

    @Serializable
    private class GithubAsset(
        val name: String,
        val size: Long,
        val digest: String? = null,
        @SerialName("browser_download_url") val url: String,
    )

    @Serializable
    private class GithubRelease(
        @SerialName("tag_name") val tag: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val body: String? = null,
        @SerialName("published_at") val publishedAt: String? = null,
        val assets: List<GithubAsset> = emptyList(),
    )

    companion object {
        const val USER_AGENT = "openframe"
        private const val CONNECT_TIMEOUT_S = 15L
        private const val READ_TIMEOUT_S = 60L
        private const val MAX_RELEASE_BYTES = 1024 * 1024
        private const val BUFFER_BYTES = 64 * 1024
        private val json = Json { ignoreUnknownKeys = true }

        /** GitHub's description of a release; anything else fails with `update_failed`. */
        fun parse(body: String): Release {
            val r =
                try {
                    json.decodeFromString(GithubRelease.serializer(), body)
                } catch (e: Exception) {
                    // The message would quote the answer; the class is enough.
                    throw UpdateException(UpdateErrorCode.UPDATE_FAILED, "GitHub's answer is not a release (${e.javaClass.simpleName})")
                }
            return Release(
                r.tag,
                r.draft,
                r.prerelease,
                r.body.orEmpty(),
                r.publishedAt,
                r.assets.map {
                    ReleaseAsset(it.name, it.size, it.digest, it.url)
                },
            )
        }
    }
}
