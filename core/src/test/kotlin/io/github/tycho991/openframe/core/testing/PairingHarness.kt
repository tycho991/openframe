package io.github.tycho991.openframe.core.testing

import io.github.tycho991.openframe.core.api.pairingRoutes
import io.github.tycho991.openframe.core.auth.AccessControl
import io.github.tycho991.openframe.core.auth.ClientRegistry
import io.github.tycho991.openframe.core.auth.ImageSigner
import io.github.tycho991.openframe.core.auth.PairingManager
import io.github.tycho991.openframe.core.auth.StoredClients
import io.github.tycho991.openframe.core.auth.StoredSecret
import io.github.tycho991.openframe.core.http.HttpPipeline
import io.github.tycho991.openframe.core.http.Router
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.store.JsonStore
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Pairing routes on a server of their own, with a controllable clock and contract check. */
class PairingHarness(
    private val dir: File,
) : AutoCloseable {
    val clock = TestClock()
    val log = AppLog(clock)
    val clients = newRegistry()
    private val signer =
        ImageSigner(JsonStore(File(dir, "secret.json"), StoredSecret.serializer(), TestScope(), log), clock).also { it.load() }
    val pairing = PairingManager(clients, clock, log, { ADMIN_URL })
    val http =
        TestHttp(
            HttpPipeline(Router().apply { pairingRoutes(pairing, clients) { ADMIN_URL } }, AccessControl(clients, signer), log)::handle,
            checkContract = true,
        )

    /** A registry that loads what [clients] stored, as after a restart. */
    fun newRegistry() = ClientRegistry(JsonStore(File(dir, "clients.json"), StoredClients.serializer(), TestScope(), log), clock)

    /** Pairs a client by code directly and returns its auth header. */
    fun pairedAuth(name: String): Map<String, String> {
        pairing.requestDisplay()
        val token = pairing.pair(pairing.display.value.code!!, name).first
        return mapOf("Authorization" to "Bearer $token")
    }

    fun pairWithSecret(secret: String) = http.request("POST", "/api/v1/pairing", """{"secret":"$secret"}""")

    fun pairWithCode(code: String) = http.request("POST", "/api/v1/pairing", """{"code":"$code"}""")

    fun errorCode(res: Res) =
        Json
            .parseToJsonElement(res.body())
            .jsonObject["error"]!!
            .jsonObject["code"]!!
            .jsonPrimitive.content

    override fun close() = http.close()

    companion object {
        const val ADMIN_URL = "http://192.168.100.215:8080/"

        /** The secret from a pairing link. */
        fun secretOf(url: String) = url.substringAfter("#pair=")
    }
}
