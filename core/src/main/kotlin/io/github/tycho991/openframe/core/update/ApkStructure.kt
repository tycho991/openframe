package io.github.tycho991.openframe.core.update

import java.io.File
import java.io.RandomAccessFile

/**
 * The ZIP structure of a downloaded APK (design decision 5 of `app-auto-update`). Android 5.0 to 8.0 without the
 * December 2017 patch verify a v1-signed APK as a ZIP, but may run DEX data placed in front of it ("Janus",
 * CVE-2017-13156). So the archive must start with its first local entry at offset 0, end exactly with its
 * end-of-central-directory record, and its central directory must end where that record begins. The APK Signing Block
 * between the last entry and the central directory stays allowed. Without `java.nio.file`, for Android 5.
 */
object ApkStructure {
    private const val LOCAL_HEADER = 0x04034b50L
    private const val CENTRAL_HEADER = 0x02014b50L
    private const val END_RECORD = 0x06054b50L
    private const val END_RECORD_BYTES = 22
    private const val CENTRAL_HEADER_BYTES = 46
    private const val MAX_COMMENT = 0xFFFF
    private const val MAX_CENTRAL_DIRECTORY = 16 * 1024 * 1024

    /** `null` if [apk] is a plain archive from its first byte to its last, otherwise what is wrong with it. */
    fun problem(apk: File): String? =
        try {
            RandomAccessFile(apk, "r").use(::check)
        } catch (e: java.io.IOException) {
            "not readable (${e.javaClass.simpleName})"
        }

    private fun check(f: RandomAccessFile): String? {
        val length = f.length()
        if (length < END_RECORD_BYTES + 4) return "too short for an archive"
        if (f.u32(0) != LOCAL_HEADER) return "data before the first entry of the archive"
        val end = endRecord(f, length) ?: return "data after the end of the archive"
        val disk = f.u16(end + 4)
        val cdDisk = f.u16(end + 6)
        val entriesHere = f.u16(end + 8)
        val entries = f.u16(end + 10)
        val cdSize = f.u32(end + 12)
        val cdOffset = f.u32(end + 16)
        if (disk != 0 || cdDisk != 0 || entriesHere != entries) return "an archive split over several disks"
        if (entries == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) return "a ZIP64 archive"
        if (cdOffset + cdSize != end) return "data between the central directory and its end"
        if (cdSize > MAX_CENTRAL_DIRECTORY) return "a central directory that is too large"
        val cd = ByteArray(cdSize.toInt())
        f.seek(cdOffset)
        f.readFully(cd)
        var pos = 0
        var first = Long.MAX_VALUE
        repeat(entries) {
            if (pos + CENTRAL_HEADER_BYTES > cd.size || cd.u32(pos) != CENTRAL_HEADER) return "a damaged central directory"
            val offset = cd.u32(pos + 42)
            if (offset >= cdOffset) return "an entry outside the archive's data"
            first = minOf(first, offset)
            pos += CENTRAL_HEADER_BYTES + cd.u16(pos + 28) + cd.u16(pos + 30) + cd.u16(pos + 32)
        }
        if (pos != cd.size) return "a damaged central directory"
        if (first != 0L) return "data before the first entry of the archive"
        return null
    }

    /** The position of the end-of-central-directory record whose comment reaches exactly to the end, or `null`. */
    private fun endRecord(
        f: RandomAccessFile,
        length: Long,
    ): Long? {
        val from = maxOf(0L, length - END_RECORD_BYTES - MAX_COMMENT)
        val tail = ByteArray((length - from).toInt())
        f.seek(from)
        f.readFully(tail)
        for (i in tail.size - END_RECORD_BYTES downTo 0) {
            if (tail.u32(i) == END_RECORD) {
                // The last record from the end decides, as for Android; it must close the file exactly.
                return if (i + END_RECORD_BYTES + tail.u16(i + 20) == tail.size) from + i else null
            }
        }
        return null
    }

    private fun RandomAccessFile.u16(at: Long): Int {
        seek(at)
        return read() or (read() shl 8)
    }

    private fun RandomAccessFile.u32(at: Long): Long {
        seek(at)
        val b = ByteArray(4)
        readFully(b)
        return b.u32(0)
    }

    private fun ByteArray.u16(at: Int): Int = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
}
