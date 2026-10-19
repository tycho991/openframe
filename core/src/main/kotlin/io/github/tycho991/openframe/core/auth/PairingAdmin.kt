package io.github.tycho991.openframe.core.auth

import io.github.tycho991.openframe.core.log.AppLog

/** Actions that are reachable only on the frame itself, not through the API. */
class PairingAdmin(
    private val clients: ClientRegistry,
    private val signer: ImageSigner,
    private val pairing: PairingManager,
    private val log: AppLog,
) {
    /** "Revoke all pairings": tokens and image signatures become invalid, new code on the frame. */
    fun resetAll() {
        clients.removeAll()
        signer.rotate()
        pairing.reset()
        log.w("pairing", "All pairings revoked on the frame")
    }
}
