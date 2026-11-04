package io.github.tycho991.openframe.core.update

import io.github.tycho991.openframe.core.nextcloud.TrustAnchors
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date

/** Spec `app-update`: Source of updates, Fixed codes of the update › Untrusted certificate (design decisions 2 and 16). */
class GithubReleasesTest {
    private val github = FakeGithub()
    private val client = github.client()

    @AfterEach
    fun stop() = github.close()

    private fun failure(block: () -> Unit): UpdateException = assertThrows<UpdateException> { block() }

    @Test
    fun `The latest release with its APK, asked for without credentials`() {
        github.publish("v0.4.0", "openframe-0.4.0.apk" to byteArrayOf(1, 2, 3))
        val fetched = client.latest(null)!!
        assertEquals("0.4.0", fetched.release.version)
        assertEquals("Notes of v0.4.0", fetched.release.notes)
        val asset = fetched.release.assets.single()
        assertEquals("openframe-0.4.0.apk", asset.name)
        assertEquals(3, asset.size)
        assertTrue(asset.digest!!.startsWith("sha256:"))
        val seen = github.requests.single()
        assertEquals("openframe", seen.headers["user-agent"])
        assertEquals("application/vnd.github+json", seen.headers["accept"])
        assertNull(seen.headers["authorization"])
        val out = ByteArrayOutputStream()
        client.download(asset.url, out, asset.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), out.toByteArray())
    }

    @Test
    fun `Drafts, pre-releases and other tags are no version`() {
        github.publish("v0.4.0", draft = true)
        assertNull(client.latest(null)!!.release.version)
        github.publish("v0.4.0", prerelease = true)
        assertNull(client.latest(null)!!.release.version)
        github.publish("nightly")
        assertNull(client.latest(null)!!.release.version)
    }

    @Test
    fun `304 for the stored ETag keeps the stored release`() {
        github.publish("v0.4.0", "openframe-0.4.0.apk" to byteArrayOf(1))
        val first = client.latest(null)!!
        assertNotNull(first.etag)
        assertNull(client.latest(first.etag))
        assertEquals(first.etag, github.requests.last().headers["if-none-match"])
    }

    @Test
    fun `404 is update_not_found`() {
        github.latest = { FakeGithub.Answer(404, """{"message":"Not Found"}""".toByteArray()) }
        assertEquals(UpdateErrorCode.UPDATE_NOT_FOUND, failure { client.latest(null) }.code)
    }

    @Test
    fun `The rate limit is reported with its reset`() {
        github.latest = {
            FakeGithub.Answer(403, "{}".toByteArray(), mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "1793800000"))
        }
        val e = failure { client.latest(null) }
        assertEquals(UpdateErrorCode.UPDATE_RATE_LIMITED, e.code)
        assertEquals(1_793_800_000_000L, e.resetAtMs)
    }

    @Test
    fun `Another error answer is update_unreachable, a body that is not a release update_failed`() {
        github.latest = { FakeGithub.Answer(502) }
        assertEquals(UpdateErrorCode.UPDATE_UNREACHABLE, failure { client.latest(null) }.code)
        github.latest = { FakeGithub.Answer(200, "[1, 2".toByteArray()) }
        assertEquals(UpdateErrorCode.UPDATE_FAILED, failure { client.latest(null) }.code)
        github.latest = { FakeGithub.Answer(200, """{"message":"no release"}""".toByteArray()) }
        assertEquals(UpdateErrorCode.UPDATE_FAILED, failure { client.latest(null) }.code)
    }

    @Test
    fun `An http base address is refused without a request`() {
        val plain = GithubReleases(github.plainBase, "tycho991/openframe", TrustAnchors.trustManager(listOf(FakeGithub.root)))
        assertEquals(UpdateErrorCode.UPDATE_FAILED, failure { plain.latest(null) }.code)
        assertTrue(github.plainRequests.isEmpty())
    }

    @Test
    fun `An http address of the APK is refused without a request`() {
        val e = failure { client.download("${github.plainBase}/openframe-0.4.0.apk", ByteArrayOutputStream(), 10) }
        assertEquals(UpdateErrorCode.UPDATE_FAILED, e.code)
        assertTrue(github.plainRequests.isEmpty())
    }

    /** Spec `app-update`: Source of updates › Redirect to http. */
    @Test
    fun `A redirect of the APK to http is not followed`() {
        github.redirects["/assets/openframe-0.4.0.apk"] = "${github.plainBase}/openframe-0.4.0.apk"
        val e = failure { client.download("${github.base}/assets/openframe-0.4.0.apk", ByteArrayOutputStream(), 10) }
        assertEquals(UpdateErrorCode.UPDATE_FAILED, e.code)
        assertTrue(github.plainRequests.isEmpty())
    }

    @Test
    fun `A redirect to another https address is followed, as to GitHub's asset host`() {
        github.assets["/assets/real.apk"] = byteArrayOf(7, 8)
        github.redirects["/assets/openframe-0.4.0.apk"] = "${github.base}/assets/real.apk"
        val out = ByteArrayOutputStream()
        client.download("${github.base}/assets/openframe-0.4.0.apk", out, 2)
        assertArrayEquals(byteArrayOf(7, 8), out.toByteArray())
    }

    /** Spec `app-update`: Fixed codes of the update › Untrusted certificate. */
    @Test
    fun `A chain under an unknown root is update_tls and names subjects and issuers`() {
        github.publish("v0.4.0")
        val e = failure { github.client(TrustAnchors.forUpdates()).latest(null) }
        assertEquals(UpdateErrorCode.UPDATE_TLS, e.code)
        assertTrue("subject CN=localhost" in e.detail!!, e.detail)
        assertTrue("issuer " in e.detail!!, e.detail)
    }

    @Test
    fun `The update client trusts the delivered roots and ISRG Root X2, YE and YR`() {
        val names = TrustAnchors.forUpdates().map { it.subjectX500Principal.name }
        assertEquals(6, names.size)
        for (cn in listOf("CN=ISRG Root X1", "CN=ISRG Root X2", "CN=Root YE", "CN=Root YR", "CN=USERTrust ECC Certification Authority")) {
            assertTrue(names.any { cn in it }, "$cn in $names")
        }
        TrustAnchors.forUpdates().forEach { it.checkValidity() }
    }

    /**
     * The chains recorded on 4 Nov 2026 validate against the update client's roots. The date is fixed, because the
     * recorded leaf certificates expire after about 90 days.
     */
    @Test
    fun `The recorded chains of GitHub validate against the update client's roots`() {
        for (host in listOf("api.github.com", "release-assets.githubusercontent.com")) {
            val chain = chain(host)
            val anchors = TrustAnchors.forUpdates().map { TrustAnchor(it, null) }.toSet()
            val params =
                PKIXParameters(anchors).apply {
                    isRevocationEnabled = false
                    date = Date.from(Instant.parse("2026-11-04T12:00:00Z"))
                }
            val path = CertificateFactory.getInstance("X.509").generateCertPath(chain)
            CertPathValidator.getInstance("PKIX").validate(path, params)
        }
    }

    private fun chain(host: String): List<X509Certificate> =
        GithubReleasesTest::class.java.getResourceAsStream("/update/chain-$host.pem")!!.use { input ->
            CertificateFactory.getInstance("X.509").generateCertificates(input).map { it as X509Certificate }
        }
}
