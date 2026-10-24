package io.github.tycho991.openframe.core.nextcloud

import java.io.ByteArrayOutputStream

/**
 * Percent-decoding of a path as the server writes it in `href`. Not `java.net.URI` (it refuses characters such as `[`,
 * `|` or `{` that servers and OkHttp let through) and not `URLDecoder` (it turns `+` into a space, which a path does
 * not do). A malformed escape stays as it is.
 */
object PathDecoding {
    fun decode(path: String): String {
        if ('%' !in path) return path
        val out = StringBuilder()
        val pending = ByteArrayOutputStream()
        var i = 0
        while (i < path.length) {
            val c = path[i]
            val byte = if (c == '%' && i + 2 < path.length) path.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (c == '%' && byte != null) {
                pending.write(byte)
                i += 3
            } else {
                if (pending.size() > 0) {
                    out.append(pending.toString(Charsets.UTF_8.name()))
                    pending.reset()
                }
                out.append(c)
                i++
            }
        }
        if (pending.size() > 0) out.append(pending.toString(Charsets.UTF_8.name()))
        return out.toString()
    }
}
