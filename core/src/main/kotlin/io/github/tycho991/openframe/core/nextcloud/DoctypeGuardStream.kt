package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.sources.SourceException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Refuses a document type in the bytes of a listing before the XML parser sees them (design decision 3 of
 * `nextcloud-hardening`, "Fallback"). Android's parser, Expat, calls `startDTD`, but a Java exception thrown there only
 * stops the parse once Expat has gone through the whole buffer it was given, expanding every entity of the internal
 * subset on the way: minutes of one busy core for a billion nested `a`. So the prolog is scanned here: a `<!` that does
 * not open a comment, before the first element, ends the read with [Refused], and so does a byte that cannot be part of
 * a UTF-8 prolog (a listing in UTF-16, whose `<!DOCTYPE` a byte scan would not see). Comments, processing instructions
 * such as `<?xml …?>` and a UTF-8 byte order mark pass. From the first element on the bytes pass unchanged: a document
 * type after it is not well-formed, and the parser stops at it itself.
 */
internal class DoctypeGuardStream(
    input: InputStream,
) : FilterInputStream(input) {
    /** The refusal, passed through the parser as an [IOException]; [failure] is what the sync reports. */
    class Refused(
        val failure: SourceException,
    ) : IOException(failure.message)

    private enum class State { START, BOM1, BOM2, PROLOG, LT, LT_BANG, LT_BANG_DASH, COMMENT, PI, PI_QUESTION, BODY }

    private var state = State.START

    /** The `-` in a row just before the current byte, inside a comment. */
    private var dashes = 0

    override fun read(): Int {
        val b = super.read()
        if (b >= 0 && state != State.BODY) scan(b)
        return b
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        val n = super.read(b, off, len)
        if (state != State.BODY) {
            var i = off
            while (i < off + n && state != State.BODY) {
                scan(b[i].toInt() and 0xFF)
                i++
            }
        }
        return n
    }

    override fun skip(n: Long): Long {
        // Skipped bytes would not be scanned; the parser does not skip, so this reads instead.
        if (state == State.BODY) return super.skip(n)
        var skipped = 0L
        while (skipped < n && read() >= 0) skipped++
        return skipped
    }

    override fun markSupported(): Boolean = false

    private fun scan(b: Int) {
        state =
            when (state) {
                State.START -> {
                    if (b == 0xEF) State.BOM1 else prolog(b)
                }

                State.BOM1 -> {
                    if (b == 0xBB) State.BOM2 else notUtf8()
                }

                State.BOM2 -> {
                    if (b == 0xBF) State.PROLOG else notUtf8()
                }

                State.PROLOG -> {
                    prolog(b)
                }

                State.LT -> {
                    when {
                        b == '?'.code -> State.PI
                        b == '!'.code -> State.LT_BANG
                        b == 0 || b >= 0x80 -> notUtf8()
                        else -> State.BODY
                    }
                }

                State.LT_BANG -> {
                    if (b == '-'.code) State.LT_BANG_DASH else doctype()
                }

                State.LT_BANG_DASH -> {
                    if (b == '-'.code) State.COMMENT.also { dashes = 0 } else doctype()
                }

                State.COMMENT -> {
                    when {
                        b == '-'.code -> State.COMMENT.also { dashes++ }
                        b == '>'.code && dashes >= 2 -> State.PROLOG
                        else -> State.COMMENT.also { dashes = 0 }
                    }
                }

                State.PI -> {
                    if (b == '?'.code) State.PI_QUESTION else State.PI
                }

                State.PI_QUESTION -> {
                    when (b) {
                        '>'.code -> State.PROLOG
                        '?'.code -> State.PI_QUESTION
                        else -> State.PI
                    }
                }

                State.BODY -> {
                    State.BODY
                }
            }
    }

    private fun prolog(b: Int): State =
        when {
            b == '<'.code -> State.LT
            b == 0 || b >= 0x80 -> notUtf8()
            else -> State.PROLOG
        }

    private fun doctype(): Nothing = throw Refused(SourceException(SourceException.Kind.PROTOCOL, PropfindParser.DOCTYPE_REFUSED))

    private fun notUtf8(): Nothing = throw Refused(SourceException(SourceException.Kind.PROTOCOL, NOT_UTF8))

    companion object {
        /** What the frame says about a listing whose start is not UTF-8. */
        const val NOT_UTF8 = "The listing is not encoded in UTF-8"
    }
}
