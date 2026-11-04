package io.github.tycho991.openframe.core.update

/**
 * The SHA-256 of the certificate that signs openframe's releases (design decision 1 of `app-auto-update`). The same
 * value is pinned as `FRAME_CERT_SHA256` in `.github/workflows/release-please.yml`; a test keeps the two equal.
 */
object OfficialCertificate {
    const val SHA256 = "2f1a67021c705203a0cb8f0324229abdd42a31d69d6c4907ca2ae41538ce34fe"

    /** Lowercase hex without colons or spaces, as both places may write it. */
    fun normalize(sha256: String): String = sha256.lowercase().filter { it != ':' && !it.isWhitespace() }

    /** Whether [sha256] is the official certificate; `null`, an unreadable certificate, is not. */
    fun matches(sha256: String?): Boolean = sha256 != null && normalize(sha256) == SHA256
}
