package io.github.tycho991.openframe.core.auth

import io.github.tycho991.openframe.core.log.AppLog

/** Actions that are reachable only on the frame itself, not through the API. */
class PairingAdmin(
    private val clients: ClientRegistry,
    private val signer: ImageSigner,
    private val pairing: PairingManager,
    private val log: AppLog,
) {
    /**
     * "Revoke all pairings": tokens and image signatures become invalid, new code on the frame. Each step is guarded,
     * so a write error neither ends the app nor keeps the frame from showing a new code (spec `admin-access`, "Reset
     * all pairings on the frame").
     */
    fun resetAll() {
        try {
            step("the pairings") { clients.removeAll() }
            step("the image key") { signer.rotate() }
        } finally {
            pairing.reset()
            log.w(TAG, "All pairings revoked on the frame")
        }
    }

    private inline fun step(
        what: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: Exception) {
            log.e(TAG, "Revoking all pairings: $what could not be stored", e)
        }
    }

    private companion object {
        const val TAG = "pairing"
    }
}
