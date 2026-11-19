package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.sources.SourceException
import io.github.tycho991.openframe.core.sources.SourceLimits
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What `POST /index.php/login/v2` returned: where the user approves and where to ask whether they did. */
data class LoginStart(
    val serverUrl: String,
    val loginUrl: String,
    val pollEndpoint: String,
    val pollToken: String,
)

sealed interface LoginPoll {
    /** The user has not approved yet. */
    data object Pending : LoginPoll

    data class Done(
        val serverUrl: String,
        val loginName: String,
        val appPassword: String,
    ) : LoginPoll
}

/**
 * Nextcloud's Login Flow v2: the user approves in the browser, the frame receives an app password. The frame never
 * sees a password the user typed. A second interface so that the sync and the API are tested without a server.
 */
interface LoginFlow {
    /** Starts a flow at [serverUrl] (already normalized). Throws [SourceException]. */
    fun start(serverUrl: String): LoginStart

    /** Asks once whether the user has approved. */
    fun poll(start: LoginStart): LoginPoll

    /** The user ID of the account, the name in the WebDAV path. It is not always the login name. */
    fun userId(
        serverUrl: String,
        loginName: String,
        appPassword: String,
    ): String

    /** Asks the server to forget the app password. Failures are the caller's to ignore. */
    fun revoke(
        serverUrl: String,
        loginName: String,
        appPassword: String,
    )
}

class OkHttpLoginFlow(
    private val http: NextcloudHttp = NextcloudHttp(),
) : LoginFlow {
    override fun start(serverUrl: String): LoginStart {
        val base = serverUrl.toHttpUrl()
        val url = base.newBuilder().addPathSegments("index.php/login/v2").build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("OCS-APIRequest", "true")
                .header("User-Agent", USER_AGENT)
                .post(EMPTY)
                .build()
        val json = json(http.execute(request, redirectAllowed = redirectRule(base)).also { http.requireSuccess(it, "The login page") })
        val poll = json["poll"]?.jsonObject
        val token = poll?.get("token")?.jsonPrimitive?.contentOrNull
        val endpoint = poll?.get("endpoint")?.jsonPrimitive?.contentOrNull
        val login = json["login"]?.jsonPrimitive?.contentOrNull
        if (token == null || endpoint == null || login == null) {
            throw SourceException(SourceException.Kind.PROTOCOL, "${base.host} does not answer like a Nextcloud server")
        }
        // The frame asks only the server it was given, and never leaves https: an answer that points elsewhere stops the
        // login before its page opens.
        checkOrigin(base, endpoint, "The poll address")
        checkOrigin(base, login, "The login page")
        return LoginStart(serverUrl, login, endpoint, token)
    }

    override fun poll(start: LoginStart): LoginPoll {
        val request =
            Request
                .Builder()
                .url(start.pollEndpoint.toHttpUrl())
                .header("OCS-APIRequest", "true")
                .header("User-Agent", USER_AGENT)
                .post(FormBody.Builder().add("token", start.pollToken).build())
                .build()
        http.execute(request, redirectAllowed = redirectRule(start.serverUrl.toHttpUrl())).use { response ->
            if (response.code == 404) return LoginPoll.Pending
            http.requireSuccess(response, "The login")
            val json = json(response)
            val login = json["loginName"]?.jsonPrimitive?.contentOrNull
            val password = json["appPassword"]?.jsonPrimitive?.contentOrNull
            val server = json["server"]?.jsonPrimitive?.contentOrNull
            if (login == null || password == null) {
                throw SourceException(SourceException.Kind.PROTOCOL, "The login ended without an app password")
            }
            return LoginPoll.Done(upgradedServer(start.serverUrl, server), login, password)
        }
    }

    override fun userId(
        serverUrl: String,
        loginName: String,
        appPassword: String,
    ): String {
        val url =
            serverUrl
                .toHttpUrl()
                .newBuilder()
                .addPathSegments("ocs/v2.php/cloud/user")
                .addQueryParameter("format", "json")
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("OCS-APIRequest", "true")
                .header("User-Agent", USER_AGENT)
                .header("Authorization", Credentials.basic(loginName, appPassword, Charsets.UTF_8))
                .get()
                .build()
        val json = json(http.execute(request).also { http.requireSuccess(it, "The account") })
        return json["ocs"]
            ?.jsonObject
            ?.get("data")
            ?.jsonObject
            ?.get("id")
            ?.jsonPrimitive
            ?.contentOrNull
            ?: throw SourceException(SourceException.Kind.PROTOCOL, "The server did not name the user")
    }

    override fun revoke(
        serverUrl: String,
        loginName: String,
        appPassword: String,
    ) {
        val url =
            serverUrl
                .toHttpUrl()
                .newBuilder()
                .addPathSegments("ocs/v2.php/core/apppassword")
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("OCS-APIRequest", "true")
                .header("User-Agent", USER_AGENT)
                .header("Authorization", Credentials.basic(loginName, appPassword, Charsets.UTF_8))
                .delete()
                .build()
        http.execute(request).use { }
    }

    /** A redirect during the login follows the same rule as the addresses the server names (spec "No downgrade during the login"). */
    private fun redirectRule(base: HttpUrl): (HttpUrl) -> SourceException? =
        { target ->
            if (LoginOrigin.accepts(base, target)) {
                null
            } else {
                SourceException(
                    SourceException.Kind.INSECURE_LOGIN,
                    "The login of ${base.host} redirects to another port or away from https. Check overwriteprotocol and " +
                        "trusted_proxies in the server's config.php",
                )
            }
        }

    private fun checkOrigin(
        base: HttpUrl,
        address: String,
        what: String,
    ) {
        when (LoginOrigin.refusal(base, address.toHttpUrlOrNull())) {
            null -> {
                return
            }

            LoginOrigin.Refusal.Downgrade -> {
                throw SourceException(
                    SourceException.Kind.INSECURE_LOGIN,
                    "$what of ${base.host} is not on https on the same port. Check overwriteprotocol and trusted_proxies " +
                        "in the server's config.php",
                )
            }

            LoginOrigin.Refusal.Elsewhere -> {
                throw SourceException(
                    SourceException.Kind.INSECURE_LOGIN,
                    "$what of ${base.host} points to another server or is no web address",
                )
            }
        }
    }

    /**
     * The server the answer names if the login rule accepts it for the one given (a move from `http` to `https` on the
     * same host), else the one given: `https` never becomes `http`.
     */
    private fun upgradedServer(
        given: String,
        named: String?,
    ): String {
        val namedUrl = named?.toHttpUrlOrNull() ?: return given
        return if (LoginOrigin.accepts(given.toHttpUrl(), namedUrl)) named.trimEnd('/') else given
    }

    private fun json(response: okhttp3.Response): JsonObject =
        response.use {
            val text =
                try {
                    val body = it.body ?: throw SourceException(SourceException.Kind.PROTOCOL, "${it.request.url.host} sent no answer")
                    LimitedResponseStream(
                        body.byteStream(),
                        http.clock,
                        "The answer of ${it.request.url.host}",
                        SourceLimits.LOGIN_BYTES,
                        http.times.loginMs,
                    ).use { input -> String(input.readBytesCompat(), Charsets.UTF_8) }
                } catch (e: java.io.IOException) {
                    throw http.bodyFailure(e, it.request.url)
                }
            try {
                ApiJson.parseToJsonElement(text) as? JsonObject
            } catch (e: kotlinx.serialization.SerializationException) {
                null
            }
                ?: throw SourceException(
                    SourceException.Kind.PROTOCOL,
                    "${it.request.url.host} does not answer like a Nextcloud server",
                )
        }

    private companion object {
        const val USER_AGENT = "openframe"
        val EMPTY = ByteArray(0).toRequestBody(null)

        /** The whole stream; its own limits end it. */
        fun java.io.InputStream.readBytesCompat(): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }
}
