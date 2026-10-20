package io.github.tycho991.openframe.core.qr

import io.nayuki.qrcodegen.QrCode
import io.nayuki.qrcodegen.QrSegment

/**
 * Modules of a QR code, without quiet zone. The frame draws it as a bitmap,
 * the API hands it to the web UI as [rows].
 */
class QrMatrix private constructor(
    /** Modules per side. */
    val size: Int,
    /** QR version, 1 to 40. */
    val version: Int,
    private val dark: BooleanArray,
) {
    /** `true` for a dark module at column [x], row [y]. */
    operator fun get(
        x: Int,
        y: Int,
    ): Boolean = dark[y * size + x]

    /** One string per row, `1` for a dark module, `0` for a light one. */
    fun rows(): List<String> =
        (0 until size).map { y ->
            buildString(size) { for (x in 0 until size) append(if (get(x, y)) '1' else '0') }
        }

    companion object {
        /** Quiet zone in modules that the QR code needs around it. */
        const val QUIET_ZONE = 4

        /** Encodes [text] as UTF-8 bytes with error correction M or higher if that fits the same version. */
        fun encode(text: String): QrMatrix {
            val segments = listOf(QrSegment.makeBytes(text.toByteArray(Charsets.UTF_8)))
            val qr = QrCode.encodeSegments(segments, QrCode.Ecc.MEDIUM)
            val dark = BooleanArray(qr.size * qr.size) { qr.getModule(it % qr.size, it / qr.size) }
            return QrMatrix(qr.size, qr.version, dark)
        }
    }
}
