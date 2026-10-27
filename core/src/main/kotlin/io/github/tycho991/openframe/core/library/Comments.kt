package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.ValidationException

/** The comment of a photo (spec `photo-library`, "Comment of a photo" and "Comment too long"). */
object Comments {
    /** Characters as the user counts them: Unicode code points, so an emoji is one. */
    const val MAX_CODE_POINTS = 500

    /** The field of the comment in `PhotoPatch` and in its errors. */
    const val FIELD = "comment"

    /**
     * [text] as it is stored: surrounding whitespace removed, then every run of control characters (line breaks
     * and the Unicode line and paragraph separators included) replaced by one space; `null` for `null` or an empty result. Trimming comes first, so the length is
     * that of the trimmed text. More than [MAX_CODE_POINTS] throws a [ValidationException] for the field `comment`.
     */
    fun normalize(text: String?): String? {
        if (text == null) return null
        val trimmed = text.trim()
        val out = StringBuilder(trimmed.length)
        var inControl = false
        for (c in trimmed) {
            if (isBreak(c)) {
                if (!inControl) out.append(' ')
                inControl = true
            } else {
                out.append(c)
                inControl = false
            }
        }
        // A control character that is not whitespace may have become a space at either end.
        val normalized = out.toString().trim()
        if (normalized.codePointCount(0, normalized.length) > MAX_CODE_POINTS) {
            throw ValidationException(mapOf(FIELD to "At most $MAX_CODE_POINTS characters"))
        }
        return normalized.ifEmpty { null }
    }

    /** A control character (line breaks included) or a Unicode line or paragraph separator, which also break lines. */
    private fun isBreak(c: Char) = Character.isISOControl(c) || c == '\u2028' || c == '\u2029'
}
