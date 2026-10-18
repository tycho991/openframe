package io.github.tycho991.openframe.core

import java.io.ByteArrayOutputStream
import java.io.InputStream

// Ersatz für InputStream.readNBytes, das es auf Android erst ab API 33 gibt.

/** Liest bis zu [max] Bytes oder bis zum Ende. */
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

/** Füllt [buf] ab [off] mit bis zu [len] Bytes. Liefert die Anzahl, kleiner als [len] nur am Ende. */
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
