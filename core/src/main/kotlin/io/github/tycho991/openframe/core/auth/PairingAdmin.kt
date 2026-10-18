package io.github.tycho991.openframe.core.auth

import io.github.tycho991.openframe.core.log.AppLog

/** Aktionen, die nur am Rahmen selbst erreichbar sind, nicht über die API. */
class PairingAdmin(
    private val clients: ClientRegistry,
    private val signer: ImageSigner,
    private val pairing: PairingManager,
    private val log: AppLog,
) {
    /** „Alle Kopplungen aufheben“: Tokens und Bild-Signaturen ungültig, neuer Code am Rahmen. */
    fun resetAll() {
        clients.removeAll()
        signer.rotate()
        pairing.reset()
        log.w("pairing", "Alle Kopplungen am Rahmen aufgehoben")
    }
}
