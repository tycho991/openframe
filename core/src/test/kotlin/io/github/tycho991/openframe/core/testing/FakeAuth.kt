package io.github.tycho991.openframe.core.testing

import io.github.tycho991.openframe.core.http.Authenticator
import io.github.tycho991.openframe.core.http.HttpRequest

/** Accepts exactly the token [token] as client [clientId]. */
class FakeAuth(
    private val token: String = "test-token",
    private val clientId: String = "client-1",
) : Authenticator {
    val header: Map<String, String> get() = mapOf("Authorization" to "Bearer $token")

    override fun clientFor(request: HttpRequest): String? = if (request.header("authorization") == "Bearer $token") clientId else null

    override fun hasValidImageSignature(
        request: HttpRequest,
        photoId: String,
    ) = false
}
