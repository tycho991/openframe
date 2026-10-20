package io.github.tycho991.openframe.core

import java.io.ByteArrayOutputStream
import java.io.InputStream

// Replacement for InputStream.readNBytes, which exists on Android only from API 33.

/** Reads up to [max] bytes or until the end. */
fun InputStream.readAtMost(max: Int): ByteArray {
    val out = ByteArrayOutputStream(minOf(max, 8192))
    val buf = ByteArray(8192)
    var left = max
    while (left > 0) {
        val n = read(buf, 0, minOf(buf.size, left))
        if (n < 0) break
        out.write(buf, 0, n)
        left -= n
    }
    return out.toByteArray()
}

/** Fills [buf] from [off] with up to [len] bytes. Returns the count, smaller than [len] only at the end. */
fun InputStream.readFully(
    buf: ByteArray,
    off: Int = 0,
    len: Int = buf.size - off,
): Int {
    var total = 0
    while (total < len) {
        val n = read(buf, off + total, len - total)
        if (n < 0) break
        total += n
    }
    return total
}
