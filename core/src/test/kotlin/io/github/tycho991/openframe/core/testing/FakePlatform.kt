package io.github.tycho991.openframe.core.testing

import io.github.tycho991.openframe.core.platform.PlatformInfo
import java.io.File

class FakePlatform(
    override val screenWidth: Int = 1024,
    override val screenHeight: Int = 600,
    override val model: String = "PFF_1024",
    override val androidVersion: String = "6.0.1",
    override val appVersion: String = "0.1.0-test",
    var free: Long? = null,
    var ips: List<String> = listOf("192.168.100.215"),
) : PlatformInfo {
    override fun ipAddresses() = ips

    override fun freeBytes(dir: File): Long = free ?: super.freeBytes(dir)
}
