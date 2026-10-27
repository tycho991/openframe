package io.github.tycho991.openframe.core.slideshow

import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.settings.CaptionSettings

/**
 * The text of the caption over a photo (spec `slideshow-display`, "Caption over the photo"): the [line] of album title
 * and comment, and the [place] in a line of its own. At least one of them is there.
 */
data class CaptionText(
    val line: String?,
    val place: String?,
) {
    companion object {
        /** Between the album title and the comment. */
        const val SEPARATOR = " · "

        /**
         * The caption of [entry], or `null` if there is nothing to show. The album title is the last level of the
         * album path, shown only when switched on and the photo has an album; [placeName] is shown only when the place
         * is switched on. The capture date is never part of it.
         */
        fun of(
            entry: PhotoEntry,
            settings: CaptionSettings,
            placeName: String?,
        ): CaptionText? {
            val title = if (settings.albumTitle) entry.album?.substringAfterLast('/') else null
            val comment = entry.comment
            val line =
                when {
                    title != null && comment != null -> title + SEPARATOR + comment
                    else -> title ?: comment
                }
            val place = if (settings.place) placeName else null
            if (line == null && place == null) return null
            return CaptionText(line, place)
        }
    }
}

/**
 * The caption for the photo of slide [seq] ([SlideState.Showing.seq]), `null` [text] if that photo has none. The
 * display switches to it when it actually swaps to that slide, and replaces the text in place for the same [seq].
 */
data class ShownCaption(
    val seq: Long,
    val text: CaptionText?,
)
