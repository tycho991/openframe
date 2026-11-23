package io.github.tycho991.openframe.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec `admin-access` › Code only while shown on the frame (design decision 9 of `android-platform-fixes`). */
class PairingShownTest {
    private val bools = listOf(false, true)

    @Test
    fun `Pairing shown on the frame`() {
        // localOnly, hintVisible, showingEmpty, noClients -> shown
        val table =
            mapOf(
                listOf(false, false, false, false) to false,
                listOf(false, false, false, true) to false,
                listOf(false, false, true, false) to false,
                listOf(false, false, true, true) to true,
                listOf(false, true, false, false) to true,
                listOf(false, true, false, true) to true,
                listOf(false, true, true, false) to true,
                listOf(false, true, true, true) to true,
            )
        for ((input, expected) in table) {
            val (localOnly, hint, empty, noClients) = input
            assertEquals(input.toString(), expected, pairingShownOnFrame(localOnly, hint, empty, noClients))
        }
        for (hint in bools) {
            for (empty in bools) {
                for (noClients in bools) {
                    assertFalse("local-only $hint $empty $noClients", pairingShownOnFrame(true, hint, empty, noClients))
                }
            }
        }
    }

    @Test
    fun `Pairing in the empty state`() {
        // localOnly, hintVisible, noClients -> shown
        val table =
            mapOf(
                listOf(false, false, false) to false,
                listOf(false, false, true) to true,
                listOf(false, true, false) to false,
                listOf(false, true, true) to false,
                listOf(true, false, false) to false,
                listOf(true, false, true) to false,
                listOf(true, true, false) to false,
                listOf(true, true, true) to false,
            )
        for ((input, expected) in table) {
            val (localOnly, hint, noClients) = input
            assertEquals(input.toString(), expected, pairingInEmptyState(localOnly, hint, noClients))
        }
    }

    @Test
    fun `Whatever shows a code counts as shown on the frame`() {
        for (localOnly in bools) {
            for (hint in bools) {
                for (empty in bools) {
                    for (noClients in bools) {
                        val inEmpty = empty && pairingInEmptyState(localOnly, hint, noClients)
                        // The hint shows code and QR code unless local-only mode is on (DisplayActivity.updateHint).
                        val inHint = hint && !localOnly
                        val shown = pairingShownOnFrame(localOnly, hint, empty, noClients)
                        if (inEmpty || inHint) assertTrue("$localOnly $hint $empty $noClients", shown)
                        if (localOnly) assertFalse("$hint $empty $noClients", shown)
                    }
                }
            }
        }
    }

    @Test
    fun `The sync sends the first value and changes only`() {
        val sent = mutableListOf<Boolean>()
        val sync = PairingShownSync { sent += it }
        sync.send(false)
        sync.send(false)
        sync.send(true)
        sync.send(true)
        sync.send(false)
        sync.send(true)
        assertEquals(listOf(false, true, false, true), sent)
        val fresh = mutableListOf<Boolean>()
        PairingShownSync { fresh += it }.send(true)
        assertEquals("a new sync sends its first value", listOf(true), fresh)
    }
}
