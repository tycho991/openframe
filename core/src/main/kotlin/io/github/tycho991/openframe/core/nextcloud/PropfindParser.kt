package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.sources.SourceException
import io.github.tycho991.openframe.core.sources.SourceLimits
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.ext.DefaultHandler2
import java.io.InputStream
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
    /** `displayname` as the server sends it, empty if it sends none: for the top of a share the name of the shared folder. */
    val displayName: String = "",
) {
    val name: String get() = path.substringAfterLast('/')
}

/**
 * Reads the answer of a `PROPFIND` with a SAX parser, which streams and keeps only the five properties of each
 * entry. `javax.xml.parsers` is on the JVM and on Android alike. It refuses a document type, collects text only
 * inside `<response>` and up to [SourceLimits.ELEMENT_CHARS] per element, and at most [maxEntries] entries (spec
 * `nextcloud-source`, "Bounded answers of a server"). The byte and time limits are the caller's stream's.
 */
object PropfindParser {
    /** What the frame says about a listing that declares a document type. */
    const val DOCTYPE_REFUSED = "The listing declares a document type (DOCTYPE), which the frame refuses"

    fun parse(
        input: InputStream,
        maxEntries: Int = SourceLimits.ENTRIES_PER_ANSWER,
        /**
         * Lets Xerces refuse a document type itself, before the [Handler] sees it. Only a test switches it off, to show
         * that the handler refuses it as well, as it must on Android, whose parser does not know the feature.
         */
        refuseDoctypeByFeature: Boolean = true,
    ): List<DavEntry> {
        val handler = Handler(maxEntries)
        try {
            val reader = newParser(refuseDoctypeByFeature).xmlReader
            reader.contentHandler = handler
            reader.errorHandler = handler
            // Called before the internal subset of a DOCTYPE is read, so no entity in it is ever expanded.
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
            reader.parse(InputSource(input))
        } catch (e: Refused) {
            throw e.failure
        } catch (e: org.xml.sax.SAXException) {
            (e.exception as? Refused)?.let { throw it.failure }
            if (LimitedResponseStream.failureIn(e) != null) throw java.io.IOException(e.message, e)
            if (e.message.orEmpty().contains(DOCTYPE_FEATURE)) throw SourceException(SourceException.Kind.PROTOCOL, DOCTYPE_REFUSED, e)
            throw SourceException(SourceException.Kind.PROTOCOL, "The listing of the folder could not be read", e)
        }
        return handler.entries
    }

    private const val DOCTYPE_FEATURE = "http://apache.org/xml/features/disallow-doctype-decl"

    private fun newParser(refuseDoctypeByFeature: Boolean): javax.xml.parsers.SAXParser {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        // No external entities or document types: the answer comes from a server that is not ours to trust blindly.
        val features =
            listOf(
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
            ) + if (refuseDoctypeByFeature) listOf(DOCTYPE_FEATURE to true) else emptyList()
        for ((feature, value) in features) {
            try {
                factory.setFeature(feature, value)
            } catch (e: Exception) {
                // The parser of the platform does not know this feature; the handler refuses a document type itself.
            }
        }
        return factory.newSAXParser()
    }

    /** A refusal of the handler, passed through the parser as a [org.xml.sax.SAXException]. */
    private class Refused(
        val failure: SourceException,
    ) : org.xml.sax.SAXException(failure.message)

    private class Handler(
        private val maxEntries: Int,
    ) : DefaultHandler2() {
        val entries = ArrayList<DavEntry>()
        private val text = StringBuilder()
        private var inResponse = false
        private var href = ""
        private var etag = ""
        private var size = 0L
        private var modified = 0L
        private var folder = false
        private var displayName = ""

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
                    displayName = ""
                }

                "collection" -> {
                    if (inResponse) folder = true
                }
            }
        }

        override fun startDTD(
            name: String?,
            publicId: String?,
            systemId: String?,
        ): Unit = throw Refused(SourceException(SourceException.Kind.PROTOCOL, DOCTYPE_REFUSED))

        override fun characters(
            ch: CharArray,
            start: Int,
            length: Int,
        ) {
            // Text outside an entry is never used, so it is not kept either.
            if (!inResponse) return
            if (text.length + length > SourceLimits.ELEMENT_CHARS) {
                throw Refused(
                    SourceException(
                        SourceException.Kind.LIMIT,
                        "An element of the listing is longer than ${SourceLimits.ELEMENT_CHARS / 1024} KiB",
                    ),
                )
            }
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

                "displayname" -> {
                    displayName = value
                }

                "response" -> {
                    inResponse = false
                    // The folder's own entry comes on top of the entries in it.
                    if (entries.size > maxEntries) {
                        throw Refused(
                            SourceException(SourceException.Kind.LIMIT, "The listing has more than $maxEntries entries in one folder"),
                        )
                    }
                    entries += DavEntry(decodedPath(href), folder, etag, size, modified, displayName)
                }
            }
            text.setLength(0)
        }

        private fun decodedPath(href: String): String = PathDecoding.decode(href.substringBefore('?')).trimEnd('/')

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
