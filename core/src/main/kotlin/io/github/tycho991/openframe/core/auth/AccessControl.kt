package io.github.tycho991.openframe.core.auth

import io.github.tycho991.openframe.core.http.Authenticator
import io.github.tycho991.openframe.core.http.HttpRequest

/** Bearer-Token aus [ClientRegistry], Bild-Signaturen aus [ImageSigner]. */
class AccessControl(
    private val clients: ClientRegistry,
    private val signer: ImageSigner,
) : Authenticator {
    override fun clientFor(request: HttpRequest): String? {
        val header = request.header("authorization") ?: return null
        if (!header.startsWith("Bearer ", ignoreCase = true)) return null
        val token = header.substring(7).trim()
        if (token.isEmpty()) return null
        return clients.authenticate(token)?.id
    }

    override fun hasValidImageSignature(
        request: HttpRequest,
        photoId: String,
    ): Boolean {
        val t = request.queryParam("t") ?: return false
        return signer.verify(photoId, t)
    }
}

/** Leitet einen Gerätenamen wie „Firefox auf Android“ aus dem User-Agent ab. */
object DeviceName {
    private val browsers =
        listOf(
            "Edg/" to "Edge",
            "EdgA/" to "Edge",
            "OPR/" to "Opera",
            "SamsungBrowser/" to "Samsung Internet",
            "FxiOS/" to "Firefox",
            "Firefox/" to "Firefox",
            "CriOS/" to "Chrome",
            "Chrome/" to "Chrome",
            "Safari/" to "Safari",
        )

    private val systems =
        listOf(
            "Android" to "Android",
            "iPhone" to "iPhone",
            "iPad" to "iPad",
            "CrOS" to "ChromeOS",
            "Windows" to "Windows",
            "Macintosh" to "macOS",
            "Mac OS X" to "macOS",
            "Linux" to "Linux",
        )

    fun fromUserAgent(userAgent: String?): String {
        if (userAgent.isNullOrBlank()) return "Unbekanntes Gerät"
        val browser = browsers.firstOrNull { userAgent.contains(it.first) }?.second
        val system = systems.firstOrNull { userAgent.contains(it.first) }?.second
        return when {
            browser != null && system != null -> {
                "$browser auf $system"
            }

            browser != null -> {
                browser
            }

            system != null -> {
                system
            }

            else -> {
                userAgent
                    .substringBefore(' ')
                    .substringBefore('/')
                    .take(32)
                    .ifBlank { "Unbekanntes Gerät" }
            }
        }
    }
}
