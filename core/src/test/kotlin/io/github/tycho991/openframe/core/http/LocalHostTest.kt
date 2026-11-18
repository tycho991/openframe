package io.github.tycho991.openframe.core.http

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec `admin-access`: Host check against DNS rebinding. */
class LocalHostTest {
    @Test
    fun `Local names`() {
        for (host in listOf(
            "192.168.100.215:8080",
            "192.168.100.215",
            "192.168.100.215.:8080",
            "frame.local:8080",
            "Frame.LOCAL",
            "frame.local.",
            "frame.fritz.box:8080",
            "FRAME.Fritz.Box.",
            "frame.home.arpa",
            "frame.localdomain",
            "frame.intranet",
            "frame.internal",
            "frame.private",
            "frame.corp",
            "frame.home",
            "frame.lan",
            "[fe80::1]:8080",
            "[::1]",
            "[FE80::1%25wlan0]:8080",
            "localhost:8080",
            "LOCALHOST",
            "frame",
            "frame-1:80",
            "android-1234abcd:65535",
        )) {
            assertTrue(isLocalHost(host), host)
        }
        assertTrue(isLocalHost(null), "A request without Host")
    }

    @Test
    fun `Rebinding page`() {
        for (host in listOf("rebind.example:8080", "evil.com", "frame.example.com.", "fritz.box", "home.arpa", "x.localhost")) {
            assertFalse(isLocalHost(host), host)
        }
    }

    @Test
    fun `Look-alike names`() {
        for (host in listOf(
            "evil.com.local.evil.com",
            "1.2.3.4.evil.com",
            "192.168.100.215.evil.com",
            "frame.local.evil.com",
            "frame.fritz.box.evil.com",
            "frame.localx",
            "framelocal.com",
            "127.1.evil",
            "1.2.3.256.5",
        )) {
            assertFalse(isLocalHost(host), host)
        }
    }

    @Test
    fun `Malformed hosts and ports`() {
        for (host in listOf(
            "",
            ".",
            "frame:0",
            "frame:65536",
            "frame:",
            "frame:80:80",
            "fe80::1",
            "[fe80::1",
            "[fe80::1]x",
            "[frame.local]",
            "a b",
            "user@frame",
            "-frame",
            "frame..local",
            "frame/x",
            "bad_host!",
            "a".repeat(64) + ".local",
            "x." + "a".repeat(260),
        )) {
            assertFalse(isLocalHost(host), host)
        }
    }
}
