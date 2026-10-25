package io.github.tycho991.openframe.core.nextcloud

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Spec `nextcloud-source`: Connect with a share link (which addresses are a share). */
class ShareLinkTest {
    private fun parse(link: String) = ShareLink.parse(link)

    @Test
    fun `A plain share link`() {
        assertEquals(ShareLink("https://cloud.example.org", "AbC123xyz"), parse("https://cloud.example.org/s/AbC123xyz"))
    }

    @Test
    fun `A link behind index php`() {
        assertEquals(ShareLink("https://cloud.example.org", "AbC123"), parse("https://cloud.example.org/index.php/s/AbC123"))
    }

    @Test
    fun `A server in a sub-path, with a port`() {
        assertEquals(ShareLink("https://nas.local:8443/nextcloud", "AbC123"), parse("https://nas.local:8443/nextcloud/s/AbC123"))
        assertEquals(ShareLink("http://nas/apps/nextcloud", "AbC123"), parse("http://nas/apps/nextcloud/index.php/s/AbC123"))
    }

    @Test
    fun `A trailing slash, spaces around it, a query or fragment and capitals in host and scheme`() {
        assertEquals(ShareLink("https://cloud.example.org", "AbC123"), parse("https://cloud.example.org/s/AbC123/"))
        assertEquals(ShareLink("https://cloud.example.org", "AbC123"), parse("  https://cloud.example.org/s/AbC123  "))
        assertEquals(ShareLink("https://cloud.example.org", "AbC123"), parse("https://cloud.example.org/s/AbC123?dir=/&openfile=true"))
        assertEquals(ShareLink("https://cloud.example.org", "AbC123"), parse("HTTPS://Cloud.Example.org/s/AbC123#x"))
    }

    @Test
    fun `Plain http is a link as well`() {
        assertEquals(ShareLink("http://192.168.100.20", "AbC123"), parse("http://192.168.100.20/s/AbC123"))
    }

    @Test
    fun `Other schemes, no s segment and an empty or odd token are refused`() {
        for (bad in listOf(
            "ftp://cloud.example.org/s/AbC123",
            "webdav://cloud.example.org/s/AbC123",
            "cloud.example.org/s/AbC123",
            "https://cloud.example.org/AbC123",
            "https://cloud.example.org/f/12345",
            "https://cloud.example.org/s/",
            "https://cloud.example.org/s",
            "https://cloud.example.org/s/AbC123/extra",
            "https://cloud.example.org/s/Ab%2FC",
            "https://cloud.example.org/s/Ab-C",
            "https://user:pw@cloud.example.org/s/AbC123",
            "https:///s/AbC123",
            "",
            "not a link",
        )) {
            assertNull(parse(bad), bad)
        }
    }

    @Test
    fun `The token is not in the text of a link`() {
        assertFalse(parse("https://cloud.example.org/s/SecretToken42").toString().contains("SecretToken42"))
    }
}
