package io.github.tycho991.openframe.core.nextcloud

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.SAXParserFactory

/** One `<response>` of a `PROPFIND` answer. */
data class DavEntry(
    /** The path of the entry as the server wrote it, decoded, without a trailing `/`. */
    val path: String,
    val isFolder: Boolean,
    val etag: String,
    val size: Long,
    val modifiedMs: Long,
) {
    val name: String get() = path.substringAfterLast('/')
}

/**
 * Reads the answer of a `PROPFIND` with a SAX parser, which streams and keeps only the four properties of each
 * entry. `javax.xml.parsers` is on the JVM and on Android alike.
 */
object PropfindParser {
    fun parse(input: InputStream): List<DavEntry> {
        val handler = Handler()
        try {
            newParser().parse(InputSource(input), handler)
        } catch (e: org.xml.sax.SAXException) {
            throw NextcloudException(NextcloudException.Kind.PROTOCOL, "The listing of the folder could not be read", e)
        }
        return handler.entries
    }

    private fun newParser(): javax.xml.parsers.SAXParser {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        // No external entities or document types: the answer comes from a server that is not ours to trust blindly.
        for ((feature, value) in listOf(
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
        )) {
            try {
                factory.setFeature(feature, value)
            } catch (e: Exception) {
                // The parser of the platform does not know this feature; it does not load external entities either.
            }
        }
        return factory.newSAXParser()
    }

    private class Handler : DefaultHandler() {
        val entries = ArrayList<DavEntry>()
        private val text = StringBuilder()
        private var inResponse = false
        private var href = ""
        private var etag = ""
        private var size = 0L
        private var modified = 0L
        private var folder = false

        override fun startElement(
            uri: String?,
            localName: String,
            qName: String?,
            attributes: Attributes?,
        ) {
            text.setLength(0)
            when (localName) {
                "response" -> {
                    inResponse = true
                    href = ""
                    etag = ""
                    size = 0L
                    modified = 0L
                    folder = false
                }

                "collection" -> {
                    if (inResponse) folder = true
                }
            }
        }

        override fun characters(
            ch: CharArray,
            start: Int,
            length: Int,
        ) {
            text.append(ch, start, length)
        }

        override fun endElement(
            uri: String?,
            localName: String,
            qName: String?,
        ) {
            if (!inResponse) return
            val value = text.toString().trim()
            when (localName) {
                "href" -> {
                    href = value
                }

                "getetag" -> {
                    etag = value.removePrefix("W/").trim('"')
                }

                "getcontentlength" -> {
                    size = value.toLongOrNull() ?: 0L
                }

                "getlastmodified" -> {
                    modified = parseDate(value)
                }

                "response" -> {
                    inResponse = false
                    entries += DavEntry(decodedPath(href), folder, etag, size, modified)
                }
            }
            text.setLength(0)
        }

        private fun decodedPath(href: String): String =
            try {
                URI(href).path ?: href
            } catch (e: java.net.URISyntaxException) {
                href
            }.trimEnd('/')

        private fun parseDate(value: String): Long =
            try {
                SimpleDateFormat(
                    "EEE, dd MMM yyyy HH:mm:ss zzz",
                    Locale.US,
                ).apply { timeZone = TimeZone.getTimeZone("GMT") }.parse(value)?.time
                    ?: 0L
            } catch (e: java.text.ParseException) {
                0L
            }
    }
}
