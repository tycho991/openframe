package io.github.tycho991.openframe.android

import io.github.tycho991.openframe.core.FrameCore
import io.github.tycho991.openframe.core.FrameDirs
import io.github.tycho991.openframe.core.platform.ApkVerdict
import io.github.tycho991.openframe.core.platform.AppInstaller
import io.github.tycho991.openframe.core.platform.AssetSource
import io.github.tycho991.openframe.core.platform.ImageInfo
import io.github.tycho991.openframe.core.platform.ImageMetadata
import io.github.tycho991.openframe.core.platform.MediaEngine
import io.github.tycho991.openframe.core.platform.MediaStoreNotifier
import io.github.tycho991.openframe.core.platform.PlatformInfo
import io.github.tycho991.openframe.core.platform.Region
import io.github.tycho991.openframe.core.update.FetchedRelease
import io.github.tycho991.openframe.core.update.OfficialCertificate
import io.github.tycho991.openframe.core.update.ReleaseSource
import io.github.tycho991.openframe.core.update.UpdateErrorCode
import io.github.tycho991.openframe.core.update.UpdateException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A light [FrameCore] for Robolectric: no photos to decode, no GitHub, a recording installer, the server on a free
 * port of the loopback address. [FrameApp.coreFactory] hands it to service and display.
 */
internal object TestCore {
    fun create(
        dir: File,
        installer: AppInstaller = RecordingInstaller(),
        platform: PlatformInfo = Platform(),
    ): FrameCore =
        FrameCore(
            FrameDirs(File(dir, "state"), File(dir, "cache"), File(dir, "photos"), File(dir, "update")),
            platform,
            NoEngine,
            AssetSource { null },
            MediaStoreNotifier.None,
            port = 0,
            bindLoopback = true,
            installer = installer,
            releases = NoReleases,
        )

    class Platform(
        override val appVersion: String = "0.3.0",
        override val appVersionCode: Long? = 300,
    ) : PlatformInfo {
        override val model = "Robolectric"
        override val androidVersion = "6.0.1"
        override val screenWidth = 1024
        override val screenHeight = 600
        override val systemLocale: Locale = Locale.ENGLISH

        override fun ipAddresses() = listOf("192.168.100.215")
    }

    /** Records its calls with the thread they ran on; allowed and official unless the test says otherwise. */
    class RecordingInstaller(
        @Volatile var allowed: Boolean = true,
    ) : AppInstaller {
        val calls = CopyOnWriteArrayList<Pair<String, String>>()

        override val installAllowed get() = allowed

        override fun installedCertificateSha256() = OfficialCertificate.SHA256

        override fun verify(apk: File) = ApkVerdict.Ok

        override fun createSession(): Int = 1.also { calls += "create" to Thread.currentThread().name }

        override fun commit(
            sessionId: Int,
            apk: File,
        ) {
            calls += "commit" to Thread.currentThread().name
        }

        override fun openSessions() = emptySet<Int>()

        override fun abandon(sessionId: Int) {
            calls += "abandon" to Thread.currentThread().name
        }
    }

    private object NoEngine : MediaEngine {
        override fun probe(file: File): ImageInfo = throw IOException("no images in this test")

        override fun readMetadata(file: File) = ImageMetadata.NONE

        override fun render(
            source: File,
            target: File,
            width: Int,
            height: Int,
            region: Region,
            quality: Int,
        ) = throw IOException("no images in this test")
    }

    private object NoReleases : ReleaseSource {
        override fun latest(etag: String?): FetchedRelease? =
            throw UpdateException(UpdateErrorCode.UPDATE_UNREACHABLE, "no GitHub in this test")

        override fun download(
            url: String,
            out: OutputStream,
            maxBytes: Long,
        ) = throw UpdateException(UpdateErrorCode.UPDATE_UNREACHABLE, "no GitHub in this test")
    }
}
