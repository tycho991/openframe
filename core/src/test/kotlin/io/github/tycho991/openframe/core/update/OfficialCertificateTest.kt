package io.github.tycho991.openframe.core.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Design decision 1 of `app-auto-update`: the certificate pinned in the app is the one the release workflow checks. */
class OfficialCertificateTest {
    private val workflow =
        File(System.getProperty("openframe.releaseWorkflow") ?: error("System property openframe.releaseWorkflow is missing"))

    @Test
    fun `The pinned certificate equals FRAME_CERT_SHA256 of the release workflow`() {
        val pinned =
            workflow
                .readLines()
                .mapNotNull { Regex("""^\s*FRAME_CERT_SHA256:\s*['"]?([0-9A-Fa-f:]+)['"]?\s*$""").find(it)?.groupValues?.get(1) }
        assertEquals(1, pinned.size, "FRAME_CERT_SHA256 is set once in ${workflow.name}")
        assertEquals(OfficialCertificate.normalize(pinned.single()), OfficialCertificate.SHA256)
    }

    @Test
    fun `Case and colons do not matter, another or no certificate does`() {
        val colons =
            OfficialCertificate.SHA256
                .uppercase()
                .chunked(2)
                .joinToString(":")
        assertTrue(OfficialCertificate.matches(colons))
        assertFalse(OfficialCertificate.matches("00" + OfficialCertificate.SHA256.drop(2)))
        assertFalse(OfficialCertificate.matches(null))
    }
}
