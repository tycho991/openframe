package io.github.tycho991.openframe.core.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QrMatrixTest {
    /** Draws the matrix with quiet zone, [scale] pixels per module, and decodes it with ZXing. */
    private fun decode(
        m: QrMatrix,
        scale: Int = 4,
    ): String {
        val side = (m.size + 2 * QrMatrix.QUIET_ZONE) * scale
        val pixels =
            IntArray(side * side) { i ->
                val x = i % side / scale - QrMatrix.QUIET_ZONE
                val y = i / side / scale - QrMatrix.QUIET_ZONE
                val dark = x in 0 until m.size && y in 0 until m.size && m[x, y]
                if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true)).text
    }

    @Test
    fun `pairing URL decodes back and fits version 4`() {
        val url = "http://192.168.100.215:8080/#pair=AbCdEfGhIjKlMnOpQrSt_-"
        val m = QrMatrix.encode(url)
        assertEquals(url, decode(m))
        assertTrue(m.version <= 4, "version ${m.version}")
        assertEquals(17 + 4 * m.version, m.size)
    }

    @Test
    fun `long host name gets a higher version`() {
        val url = "http://a-rather-long-host-name-for-the-picture-frame.home.arpa:8080/#pair=AbCdEfGhIjKlMnOpQrSt_-"
        val m = QrMatrix.encode(url)
        assertEquals(url, decode(m))
        assertTrue(m.version > 4)
    }

    @Test
    fun `rows hold zeros and ones per module`() {
        val m = QrMatrix.encode("x")
        val rows = m.rows()
        assertEquals(m.size, rows.size)
        assertTrue(rows.all { it.length == m.size && it.all { c -> c == '0' || c == '1' } })
        // Top left finder pattern: the first row starts with seven dark modules.
        assertTrue(rows[0].startsWith("1111111"))
        assertEquals(m[3, 0], rows[0][3] == '1')
    }
}
