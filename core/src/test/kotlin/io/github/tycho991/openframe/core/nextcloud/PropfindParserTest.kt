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

    // --- Spec nextcloud-source: Bounded answers of a server (design decision 3 of nextcloud-hardening)

    private val plainDoctype =
        """<?xml version="1.0"?><!DOCTYPE multistatus [<!ELEMENT multistatus ANY>]><d:multistatus xmlns:d="DAV:"><d:response><d:href>/a</d:href></d:response></d:multistatus>"""

    @Test
    fun `A document type without entities is refused by the handler itself, as on Android`() {
        // Without the Xerces feature, which Android's parser does not know: only the lexical handler can refuse it.
        val failure =
            assertThrows(
                SourceException::class.java,
            ) { PropfindParser.parse(plainDoctype.byteInputStream(), refuseDoctypeByFeature = false) }
        assertEquals(SourceException.Kind.PROTOCOL, failure.kind)
        assertEquals(PropfindParser.DOCTYPE_REFUSED, failure.message)
    }

    @Test
    fun `A document type is refused with the same text when Xerces refuses it first`() {
        val failure = assertThrows(SourceException::class.java) { parse(plainDoctype) }
        assertEquals(SourceException.Kind.PROTOCOL, failure.kind)
        assertEquals(PropfindParser.DOCTYPE_REFUSED, failure.message)
    }

    @Test
    fun `Nested entities are never expanded`() {
        val lol =
            """<?xml version="1.0"?><!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">""" +
                """<!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">]>""" +
                """<d:multistatus xmlns:d="DAV:"><d:response><d:href>&lol3;</d:href></d:response></d:multistatus>"""
        for (feature in listOf(true, false)) {
            val failure =
                assertThrows(SourceException::class.java) { PropfindParser.parse(lol.byteInputStream(), refuseDoctypeByFeature = feature) }
            assertEquals(PropfindParser.DOCTYPE_REFUSED, failure.message)
        }
    }

    private fun response(href: String) =
        "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat></d:response>"

    @Test
    fun `Text up to 16 KiB per element passes, more is a limit`() {
        val fits = "/" + "a".repeat(16 * 1024 - 1)
        assertEquals(fits, parse("""<d:multistatus xmlns:d="DAV:">${response(fits)}</d:multistatus>""").single().path)
        val failure =
            assertThrows(SourceException::class.java) {
                parse("""<d:multistatus xmlns:d="DAV:">${response("/" + "a".repeat(16 * 1024))}</d:multistatus>""")
            }
        assertEquals(SourceException.Kind.LIMIT, failure.kind)
        assertTrue(failure.message!!.contains("16 KiB"), failure.message)
    }

    @Test
    fun `Text outside an entry is not kept`() {
        val padding = " ".repeat(1024 * 1024)
        assertEquals(1, parse("""<d:multistatus xmlns:d="DAV:">$padding${response("/a")}$padding</d:multistatus>""").size)
    }

    @Test
    fun `More entries than the limit end the listing`() {
        // The folder's own entry and 10 entries in it pass a limit of 10, one more does not.
        val fits = """<d:multistatus xmlns:d="DAV:">""" + (0..10).joinToString("") { response("/f$it") } + "</d:multistatus>"
        assertEquals(11, PropfindParser.parse(fits.byteInputStream(), maxEntries = 10).size)
        val more = """<d:multistatus xmlns:d="DAV:">""" + (0..11).joinToString("") { response("/f$it") } + "</d:multistatus>"
        val failure = assertThrows(SourceException::class.java) { PropfindParser.parse(more.byteInputStream(), maxEntries = 10) }
        assertEquals(SourceException.Kind.LIMIT, failure.kind)
    }
}

/** Review finding 9 of `nextcloud-hardening`: names, namespaces and attribute values are bounded as the text is. */
class PropfindParserNamesTest {
    private val long = "a".repeat(16 * 1024 + 1)

    /** Without the JDK's own limit of 1000 characters per name (raised here), which Android's parser does not have. */
    private fun limitOf(xml: String): SourceException {
        val before = System.getProperty("jdk.xml.maxXMLNameLimit")
        System.setProperty("jdk.xml.maxXMLNameLimit", "100000")
        try {
            return assertThrows(SourceException::class.java) { PropfindParser.parse(xml.byteInputStream()) }
        } finally {
            if (before == null) System.clearProperty("jdk.xml.maxXMLNameLimit") else System.setProperty("jdk.xml.maxXMLNameLimit", before)
        }
    }

    @Test
    fun `A long attribute value, element name or namespace is a limit`() {
        for (xml in listOf(
            """<d:multistatus xmlns:d="DAV:"><d:response x="$long"><d:href>/a</d:href></d:response></d:multistatus>""",
            """<d:multistatus xmlns:d="DAV:"><d:response><d:$long/><d:href>/a</d:href></d:response></d:multistatus>""",
            """<d:multistatus xmlns:d="DAV:" xmlns:x="urn:$long"><d:response><d:href>/a</d:href></d:response></d:multistatus>""",
        )) {
            val failure = limitOf(xml)
            assertEquals(SourceException.Kind.LIMIT, failure.kind, "${failure.message} ${failure.cause}")
        }
        // Short ones pass.
        val fine = """<d:multistatus xmlns:d="DAV:" xmlns:x="urn:x"><d:response x="1"><d:href>/a</d:href></d:response></d:multistatus>"""
        assertEquals(1, PropfindParser.parse(fine.byteInputStream()).size)
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
