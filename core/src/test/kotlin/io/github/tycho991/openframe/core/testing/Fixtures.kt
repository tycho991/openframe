package io.github.tycho991.openframe.core.testing

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Test images from `src/test/resources/fixtures`, generated with `scripts/generate-test-fixtures.py`. */
object Fixtures {
    fun file(name: String): File = File(requireNotNull(javaClass.getResource("/fixtures/$name")) { "Fixture $name is missing" }.toURI())

    /**
     * The signed test APK (`update-0.4.0.apk`) with [comment] as its archive comment: a valid archive whose bytes, and so
     * whose checksum, differ for each comment.
     */
    fun apk(comment: String): ByteArray {
        val bytes = file("update-0.4.0.apk").readBytes()
        val text = comment.toByteArray()
        // The end-of-central-directory record is the last 22 bytes; its last two give the comment's length.
        bytes[bytes.size - 2] = (text.size and 0xFF).toByte()
        bytes[bytes.size - 1] = (text.size shr 8).toByte()
        return bytes + text
    }

    /** Copies the fixture [name] to [target] and creates folders. */
    fun copy(
        name: String,
        target: File,
    ): File {
        target.parentFile.mkdirs()
        return file(name).copyTo(target, overwrite = true)
    }

    /**
     * A grey PNG that declares [width]×[height] but holds only one row of pixel data: enough for every reader of the
     * bounds, and far too little to decode, so an image of gigapixels costs a few bytes.
     */
    fun pngOfSize(
        width: Int,
        height: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)

        fun chunk(
            type: String,
            body: ByteArray,
        ) {
            data.writeInt(body.size)
            val typed = type.toByteArray(Charsets.US_ASCII) + body
            data.write(typed)
            data.writeInt(CRC32().apply { update(typed) }.value.toInt())
        }
        data.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val header = ByteArrayOutputStream()
        DataOutputStream(header).apply {
            writeInt(width)
            writeInt(height)
            write(byteArrayOf(8, 0, 0, 0, 0))
        }
        chunk("IHDR", header.toByteArray())
        val row = ByteArray(minOf(width, 4096) + 1)
        val deflated = ByteArray(row.size + 64)
        val n =
            Deflater().run {
                setInput(row)
                finish()
                deflate(deflated).also { end() }
            }
        chunk("IDAT", deflated.copyOf(n))
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }
}
