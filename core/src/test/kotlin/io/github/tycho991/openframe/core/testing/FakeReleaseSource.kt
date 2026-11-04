package io.github.tycho991.openframe.core.testing

import io.github.tycho991.openframe.core.update.FetchedRelease
import io.github.tycho991.openframe.core.update.Release
import io.github.tycho991.openframe.core.update.ReleaseAsset
import io.github.tycho991.openframe.core.update.ReleaseSource
import io.github.tycho991.openframe.core.update.UpdateErrorCode
import io.github.tycho991.openframe.core.update.UpdateException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** GitHub's releases in memory, for the updater's tests and the test server. Answers 304 for the ETag it gave. */
class FakeReleaseSource : ReleaseSource {
    /** The latest release; `null` answers like GitHub without a release (404). */
    @Volatile var release: Release? = null

    /** The files by their address. */
    val files = ConcurrentHashMap<String, ByteArray>()

    /** Thrown by the next requests for the latest release while set. */
    @Volatile var failure: UpdateException? = null

    /** Thrown by downloads while set. */
    @Volatile var downloadFailure: UpdateException? = null

    /** Flips a byte of every download, as a damaged transfer would. */
    @Volatile var corrupt = false

    val requests = AtomicInteger()
    val downloads = AtomicInteger()

    /**
     * Publishes [version] with [apk] as its only APK, by default the signed test APK with a checksum of its own, and
     * its SHA-256 as the asset's digest unless [digest] says otherwise.
     */
    fun publish(
        version: String,
        apk: ByteArray = Fixtures.apk(version),
        notes: String = "Fixes for $version",
        digest: String? = "sha256:" + sha256(apk),
        assetName: String = "openframe-$version.apk",
    ) {
        val url = "https://release-assets.example.com/$version/$assetName"
        files[url] = apk
        release =
            Release(
                "v$version",
                notes = notes,
                publishedAt = "2026-11-01T10:00:00Z",
                assets = listOf(ReleaseAsset(assetName, apk.size.toLong(), digest, url)),
            )
    }

    override fun latest(etag: String?): FetchedRelease? {
        requests.incrementAndGet()
        failure?.let { throw it }
        val r = release ?: throw UpdateException(UpdateErrorCode.UPDATE_NOT_FOUND, "GitHub has no release of openframe (HTTP 404)")
        val tag = "\"" + sha256(r.toString().toByteArray()).take(16) + "\""
        return if (etag == tag) null else FetchedRelease(r, tag)
    }

    override fun download(
        url: String,
        out: OutputStream,
        maxBytes: Long,
    ) {
        downloads.incrementAndGet()
        downloadFailure?.let { throw it }
        val bytes = files[url] ?: throw UpdateException(UpdateErrorCode.UPDATE_UNREACHABLE, "release-assets answered with HTTP 404")
        out.write(if (corrupt) bytes.copyOf().also { it[0] = (it[0] + 1).toByte() } else bytes)
    }

    companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
