@file:Suppress("ktlint:standard:max-line-length")

package io.github.tycho991.openframe.core.nextcloud

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PropfindParserTest {
    private fun parse(xml: String) = PropfindParser.parse(xml.byteInputStream())

    @Test
    fun `Folders and files with their properties`() {
        val entries =
            parse(
                """<?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
                  <d:response><d:href>/remote.php/dav/files/anna/Photos/</d:href>
                    <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><d:getetag>"abc"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                  <d:response><d:href>/remote.php/dav/files/anna/Photos/Sommer%20Urlaub/</d:href>
                    <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                  <d:response><d:href>/remote.php/dav/files/anna/Photos/M%C3%BCnchen.jpg</d:href>
                    <d:propstat><d:prop><d:resourcetype/><d:getetag>W/"e1"</d:getetag><d:getcontentlength>2048</d:getcontentlength>
                      <d:getlastmodified>Wed, 15 Oct 2025 08:30:00 GMT</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                    <d:propstat><d:prop><oc:size/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>
                </d:multistatus>""",
            )
        assertEquals(3, entries.size)
        assertTrue(entries[0].isFolder)
        assertEquals("/remote.php/dav/files/anna/Photos", entries[0].path)
        assertEquals("Sommer Urlaub", entries[1].name)
        assertTrue(entries[1].isFolder)
        val file = entries[2]
        assertFalse(file.isFolder)
        assertEquals("München.jpg", file.name)
        assertEquals("e1", file.etag, "quotes and the weak marker are dropped")
        assertEquals(2048, file.size)
        assertEquals(1_760_517_000_000L, file.modifiedMs)
    }

    @Test
    fun `An answer that is no XML is a protocol error`() {
        val failure = assertThrows(NextcloudException::class.java) { parse("<html><body>Maintenance") }
        assertEquals(NextcloudException.Kind.PROTOCOL, failure.kind)
    }

    @Test
    fun `A document type is not loaded`() {
        val xml = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><d:multistatus xmlns:d="DAV:"><d:response><d:href>&e;</d:href></d:response></d:multistatus>"""
        val entries =
            try {
                parse(xml)
            } catch (e: NextcloudException) {
                emptyList()
            }
        assertTrue(entries.none { "root:" in it.path })
    }
}
