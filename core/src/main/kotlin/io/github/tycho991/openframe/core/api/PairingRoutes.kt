package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.auth.ClientRegistry
import io.github.tycho991.openframe.core.auth.DeviceName
import io.github.tycho991.openframe.core.auth.PairingManager
import io.github.tycho991.openframe.core.auth.StoredClient
import io.github.tycho991.openframe.core.http.Access
import io.github.tycho991.openframe.core.http.HttpException
import io.github.tycho991.openframe.core.http.HttpResponse
import io.github.tycho991.openframe.core.http.Router
import io.github.tycho991.openframe.core.http.isLocalHost
import io.github.tycho991.openframe.core.http.parseHost
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.qr.QrMatrix
import io.github.tycho991.openframe.core.singleLine
import kotlinx.serialization.Serializable

@Serializable
data class PairingRequestDto(
    val code: String? = null,
    val secret: String? = null,
    val deviceName: String? = null,
)

@Serializable
data class ClientUpdateDto(
    val name: String,
)

@Serializable
data class InviteDto(
    val url: String,
    val expiresAt: String,
    val qr: List<String>,
)

@Serializable
data class ClientDto(
    val id: String,
    val name: String,
    val pairedAt: String,
    val lastSeenAt: String,
    val current: Boolean,
)

@Serializable
data class PairingResponseDto(
    val token: String,
    val client: ClientDto,
)

@Serializable
data class ClientListDto(
    val clients: List<ClientDto>,
)

private fun StoredClient.toDto(currentId: String?) = ClientDto(id, name, pairedAt.toString(), lastSeenAt.toString(), id == currentId)

/** A pairing body without a token is small (spec `admin-access`, Size of request bodies without token). */
private const val MAX_PAIRING_BODY = 4L * 1024

private val CODE = Regex("[0-9]{6}")
private val SECRET = Regex("[A-Za-z0-9_-]{22}")

/**
 * The admin address for the `Host` header of a request, e.g. `http://192.168.100.215:8080/`,
 * or `null` if the header is missing or not a local host with optional port ([isLocalHost]).
 */
internal fun adminUrlFromHost(host: String?): String? {
    if (host == null || parseHost(host) == null || !isLocalHost(host)) return null
    return "http://$host/"
}

private const val TAG = "pairing"

/** Pairing by code, secret or invitation and management of the paired devices. Renaming, revoking and inviting are logged with
 * the IDs of the acting and the affected device (spec `admin-access`, "Log of device management"). */
fun Router.pairingRoutes(
    pairing: PairingManager,
    clients: ClientRegistry,
    log: AppLog? = null,
    adminUrl: () -> String?,
) {
    post("/api/v1/pairing", Access.PUBLIC) { req ->
        val body = req.bodyAs(PairingRequestDto.serializer(), maxBytes = MAX_PAIRING_BODY)
        val code = body.code
        val secret = body.secret
        if ((code == null) == (secret == null)) {
            throw HttpException.badRequest("Send either a code or a secret", mapOf("code" to "Either code or secret"))
        }
        if (code != null && !code.matches(CODE)) {
            throw HttpException.badRequest("The code has six digits", mapOf("code" to "Six digits"))
        }
        if (secret != null && !secret.matches(SECRET)) {
            throw HttpException.badRequest("The secret is malformed", mapOf("secret" to "22 Base64url characters"))
        }
        val name =
            body.deviceName
                ?.let(::singleLine)
                ?.take(64)
                ?.ifBlank { null } ?: DeviceName.fromUserAgent(req.header("user-agent"))
        val (token, client) = if (code != null) pairing.pair(code, name) else pairing.pairWithSecret(secret!!, name)
        HttpResponse.json(PairingResponseDto.serializer(), PairingResponseDto(token, client.toDto(client.id)), 201)
    }
    post("/api/v1/pairing/invites") { req ->
        val base =
            adminUrlFromHost(req.header("host")) ?: adminUrl()
                ?: throw HttpException(503, "no_address", "The frame has no network address for the invitation link")
        val invite = pairing.createInvite(req.clientId!!, base)
        log?.i(TAG, "Device ${req.clientId} created an invitation")
        HttpResponse.json(
            InviteDto.serializer(),
            InviteDto(invite.url, invite.expiresAt.toString(), QrMatrix.encode(invite.url).rows()),
            201,
        )
    }
    post("/api/v1/pairing/code", Access.PUBLIC) {
        pairing.requestDisplay()
        HttpResponse.noContent()
    }
    get("/api/v1/clients") { req ->
        HttpResponse.json(ClientListDto.serializer(), ClientListDto(clients.clients.value.map { it.toDto(req.clientId) }))
    }
    patch("/api/v1/clients/{clientId}") { req ->
        val name = singleLine(req.bodyAs(ClientUpdateDto.serializer()).name)
        if (name.isEmpty() || name.length > 64) {
            throw HttpException.badRequest("The name has 1 to 64 characters", mapOf("name" to "1 to 64 characters"))
        }
        val client = clients.rename(req.pathParam("clientId"), name) ?: throw HttpException.notFound("No such device")
        log?.i(TAG, "Device ${req.clientId} renamed ${client.id} to ${client.name}")
        HttpResponse.json(ClientDto.serializer(), client.toDto(req.clientId))
    }
    delete("/api/v1/clients/{clientId}") { req ->
        val id = req.pathParam("clientId")
        // The name is read before the client goes, for the log line.
        val name =
            clients.clients.value
                .firstOrNull { it.id == id }
                ?.name
        if (!clients.remove(id)) throw HttpException.notFound("No such device")
        pairing.dropInvite(id)
        log?.i(TAG, "Device ${req.clientId} revoked $id: ${name ?: "?"}")
        HttpResponse.noContent()
    }
}
