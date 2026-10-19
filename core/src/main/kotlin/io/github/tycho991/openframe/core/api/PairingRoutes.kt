package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.auth.ClientRegistry
import io.github.tycho991.openframe.core.auth.DeviceName
import io.github.tycho991.openframe.core.auth.PairingManager
import io.github.tycho991.openframe.core.auth.StoredClient
import io.github.tycho991.openframe.core.http.Access
import io.github.tycho991.openframe.core.http.HttpException
import io.github.tycho991.openframe.core.http.HttpResponse
import io.github.tycho991.openframe.core.http.Router
import kotlinx.serialization.Serializable

@Serializable
data class PairingRequestDto(
    val code: String,
    val deviceName: String? = null,
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

/** Pairing by code and management of the paired devices. */
fun Router.pairingRoutes(
    pairing: PairingManager,
    clients: ClientRegistry,
) {
    post("/api/v1/pairing", Access.PUBLIC) { req ->
        val body = req.bodyAs(PairingRequestDto.serializer())
        if (!body.code.matches(Regex("[0-9]{6}"))) {
            throw HttpException.badRequest(
                "The code has six digits",
                mapOf("code" to "Six digits"),
            )
        }
        val name =
            body.deviceName
                ?.trim()
                ?.take(64)
                ?.ifBlank { null } ?: DeviceName.fromUserAgent(req.header("user-agent"))
        val (token, client) = pairing.pair(body.code, name)
        HttpResponse.json(PairingResponseDto.serializer(), PairingResponseDto(token, client.toDto(client.id)), 201)
    }
    post("/api/v1/pairing/code", Access.PUBLIC) {
        pairing.requestDisplay()
        HttpResponse.noContent()
    }
    get("/api/v1/clients") { req ->
        HttpResponse.json(ClientListDto.serializer(), ClientListDto(clients.clients.value.map { it.toDto(req.clientId) }))
    }
    delete("/api/v1/clients/{clientId}") { req ->
        if (!clients.remove(req.pathParam("clientId"))) throw HttpException.notFound("No such device")
        HttpResponse.noContent()
    }
}
