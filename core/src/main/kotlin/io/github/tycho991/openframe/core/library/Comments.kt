package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.ValidationException
import io.github.tycho991.openframe.core.singleLine

/** The comment of a photo (spec `photo-library`, "Comment of a photo" and "Comment too long"). */
object Comments {
    /** Characters as the user counts them: Unicode code points, so an emoji is one. */
    const val MAX_CODE_POINTS = 500

    /** The field of the comment in `PhotoPatch` and in its errors. */
    const val FIELD = "comment"

    /**
     * [text] as it is stored: [singleLine], `null` for `null` or an empty result. The length is that of the result.
     * More than [MAX_CODE_POINTS] throws a [ValidationException] for the field `comment`.
     */
    fun normalize(text: String?): String? {
        if (text == null) return null
        val normalized = singleLine(text)
        if (normalized.codePointCount(0, normalized.length) > MAX_CODE_POINTS) {
            throw ValidationException(mapOf(FIELD to "At most $MAX_CODE_POINTS characters"))
        }
        return normalized.ifEmpty { null }
    }
}
