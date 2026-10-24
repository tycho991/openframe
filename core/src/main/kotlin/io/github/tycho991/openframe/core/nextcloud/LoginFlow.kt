package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.http.ApiJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.FormBody
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
    /** Starts a flow at [serverUrl] (already normalized). Throws [NextcloudException]. */
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
        val json = json(http.execute(request).also { http.requireSuccess(it, "The login page") })
        val poll = json["poll"]?.jsonObject
        val token = poll?.get("token")?.jsonPrimitive?.contentOrNull
        val endpoint = poll?.get("endpoint")?.jsonPrimitive?.contentOrNull
        val login = json["login"]?.jsonPrimitive?.contentOrNull
        if (token == null || endpoint == null || login == null) {
            throw NextcloudException(NextcloudException.Kind.PROTOCOL, "${base.host} does not answer like a Nextcloud server")
        }
        // The frame asks only the server it was given; an answer that points elsewhere is not followed.
        val endpointUrl = endpoint.toHttpUrlOrNull()
        if (endpointUrl == null || endpointUrl.host != base.host) {
            throw NextcloudException(NextcloudException.Kind.PROTOCOL, "The login of ${base.host} points to another server")
        }
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
        http.execute(request).use { response ->
            if (response.code == 404) return LoginPoll.Pending
            http.requireSuccess(response, "The login")
            val json = json(response)
            val login = json["loginName"]?.jsonPrimitive?.contentOrNull
            val password = json["appPassword"]?.jsonPrimitive?.contentOrNull
            val server = json["server"]?.jsonPrimitive?.contentOrNull
            if (login == null || password == null) {
                throw NextcloudException(NextcloudException.Kind.PROTOCOL, "The login ended without an app password")
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
                .header("Authorization", Credentials.basic(loginName, appPassword))
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
            ?: throw NextcloudException(NextcloudException.Kind.PROTOCOL, "The server did not name the user")
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
                .header("Authorization", Credentials.basic(loginName, appPassword))
                .delete()
                .build()
        http.execute(request).use { }
    }

    /** The server the answer names if it is the same host as the one given (a move from `http` to `https`), else the one given. */
    private fun upgradedServer(
        given: String,
        named: String?,
    ): String {
        val namedUrl = named?.toHttpUrlOrNull() ?: return given
        return if (namedUrl.host == given.toHttpUrl().host) named.trimEnd('/') else given
    }

    private fun json(response: okhttp3.Response): JsonObject =
        response.use {
            val text = it.body?.string().orEmpty()
            try {
                ApiJson.parseToJsonElement(text) as? JsonObject
            } catch (e: kotlinx.serialization.SerializationException) {
                null
            }
                ?: throw NextcloudException(
                    NextcloudException.Kind.PROTOCOL,
                    "${it.request.url.host} does not answer like a Nextcloud server",
                )
        }

    private companion object {
        const val USER_AGENT = "openframe"
        val EMPTY = ByteArray(0).toRequestBody(null)
    }
}
