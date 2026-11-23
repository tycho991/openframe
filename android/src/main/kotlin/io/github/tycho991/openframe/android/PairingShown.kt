package io.github.tycho991.openframe.android

/**
 * Whether the frame shows pairing, code and QR code, in the hint or in the empty state while no device is paired
 * (spec `admin-access`, Code only while shown on the frame). Neither shows any in local-only mode. Whether the screen
 * is usable, on and not dark, the core adds itself.
 */
fun pairingShownOnFrame(
    localOnly: Boolean,
    hintVisible: Boolean,
    showingEmpty: Boolean,
    noClients: Boolean,
): Boolean = !localOnly && (hintVisible || (showingEmpty && noClients))

/**
 * Whether the empty state, when it is up, shows code and QR code: only while no device is paired, not in local-only
 * mode, and not while the hint is up, which shows the same code and QR code on top of it.
 */
fun pairingInEmptyState(
    localOnly: Boolean,
    hintVisible: Boolean,
    noClients: Boolean,
): Boolean = noClients && !localOnly && !hintVisible

/**
 * Passes a value on to [set] only when it differs from the last one; the first value always goes out. One per attach
 * of the display, used on the main thread only.
 */
class PairingShownSync(
    private val set: (Boolean) -> Unit,
) {
    private var last: Boolean? = null

    fun send(shown: Boolean) {
        if (shown == last) return
        last = shown
        set(shown)
    }
}
