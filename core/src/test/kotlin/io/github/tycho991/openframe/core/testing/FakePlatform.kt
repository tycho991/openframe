package io.github.tycho991.openframe.core.testing

import io.github.tycho991.openframe.core.platform.FolderLister
import io.github.tycho991.openframe.core.platform.PlatformInfo
import java.io.File
import java.util.Locale

class FakePlatform(
    override val screenWidth: Int = 1024,
    override val screenHeight: Int = 600,
    override val model: String = "PFF_1024",
    override val androidVersion: String = "6.0.1",
    override var appVersion: String = "0.1.0-test",
    override var appVersionCode: Long? = 100,
    override var appDebuggable: Boolean = false,
    override var otherActivityEndsApp: Boolean = false,
    var free: Long? = null,
    /** The size of the data volume, `null` for the real one of the folder. */
    var total: Long? = null,
    var ips: List<String> = listOf("192.168.100.215"),
    override var systemLocale: Locale = Locale.ENGLISH,
    var mounted: Boolean = true,
) : PlatformInfo {
    /** The free bytes of a folder, ahead of [free]; for storage that changes with what the frame deletes. */
    @Volatile var freeOf: ((File) -> Long)? = null

    override fun storageMounted(dir: File) = mounted

    override fun ipAddresses() = ips

    override fun freeBytes(dir: File): Long = freeOf?.invoke(dir) ?: free ?: super.freeBytes(dir)

    override fun totalBytes(dir: File): Long = total ?: super.totalBytes(dir)

    /** The platform's own lister of the photo folder; `null` for the JVM default. Read when the core starts. */
    var lister: FolderLister? = null

    override val folderLister: FolderLister get() = lister ?: super.folderLister
}
