@file:Suppress("ktlint:standard:max-line-length")

package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.sources.SourceException
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
        assertEquals("", file.displayName, "no displayname sent")
    }

    @Test
    fun `The display name of the top of a share`() {
        val entries =
            parse(
                """<?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:">
                  <d:response><d:href>/public.php/webdav/</d:href>
                    <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><d:displayname>Urlaub &amp; Familie</d:displayname></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                </d:multistatus>""",
            )
        assertEquals("Urlaub & Familie", entries.single().displayName)
        assertEquals("/public.php/webdav", entries.single().path, "the path stays the address, not the name")
    }

    @Test
    fun `An answer that is no XML is a protocol error`() {
        val failure = assertThrows(SourceException::class.java) { parse("<html><body>Maintenance") }
        assertEquals(SourceException.Kind.PROTOCOL, failure.kind)
    }

    @Test
    fun `A document type is not loaded`() {
        val xml = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><d:multistatus xmlns:d="DAV:"><d:response><d:href>&e;</d:href></d:response></d:multistatus>"""
        val entries =
            try {
                parse(xml)
            } catch (e: SourceException) {
                emptyList()
            }
        assertTrue(entries.none { "root:" in it.path })
    }
}

class PathDecodingTest {
    @Test
    fun `Escapes become characters and a plus stays a plus`() {
        assertEquals("/a b/München+Ulm.jpg", PathDecoding.decode("/a%20b/M%C3%BCnchen+Ulm.jpg"))
        assertEquals("/x+y", PathDecoding.decode("/x%2By"))
    }

    @Test
    fun `Characters that java net URI refuses are kept`() {
        assertEquals("/Fotos [2025]/a|b {c}.jpg", PathDecoding.decode("/Fotos%20[2025]/a|b%20{c}.jpg"))
        assertEquals("/Fotos [2025]/a|b {c}.jpg", PathDecoding.decode("/Fotos [2025]/a|b {c}.jpg"))
    }

    @Test
    fun `A malformed escape stays as it is`() {
        assertEquals("/100%", PathDecoding.decode("/100%"))
        assertEquals("/a%zzb", PathDecoding.decode("/a%zzb"))
        assertEquals("/a%2", PathDecoding.decode("/a%2"))
    }
}
