package io.github.tycho991.openframe.core

/**
 * [text] as one line: every run of control characters (line breaks included) and Unicode line or paragraph
 * separators replaced by one space, then surrounding whitespace removed. For comments, device names and the frame
 * name, whose length rules apply to the result.
 */
fun singleLine(text: String): String {
    val out = StringBuilder(text.length)
    var inControl = false
    for (c in text) {
        if (Character.isISOControl(c) || c == '\u2028' || c == '\u2029') {
            if (!inControl) out.append(' ')
            inControl = true
        } else {
            out.append(c)
            inControl = false
        }
    }
    return out.toString().trim()
}
