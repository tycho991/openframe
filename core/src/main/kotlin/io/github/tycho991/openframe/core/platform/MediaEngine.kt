package io.github.tycho991.openframe.core.platform

import java.io.File
import java.time.Instant

enum class ImageFormat(
    val extension: String,
) {
    JPEG("jpg"),
    PNG("png"),
    WEBP("webp"),
    GIF("gif"),
    HEIF("heic"),
}

/** Maße nach EXIF-Drehung und Aufnahmedatum aus EXIF, sofern vorhanden. */
data class ImageInfo(
    val format: ImageFormat,
    val width: Int,
    val height: Int,
    val takenAt: Instant?,
)

/** Warum eine Datei mit Bild-Endung nicht aufgenommen werden kann. */
enum class SkipReason(
    val apiName: String,
) {
    /** Format auf diesem Gerät nicht decodierbar, etwa HEIC vor API 28. */
    UNSUPPORTED("unsupported"),

    /** Datei beschädigt oder kein Bild. */
    CORRUPT("corrupt"),
}

class ImageException(
    val reason: SkipReason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Bildoperationen der Plattform. In :android über `BitmapFactory`, in Tests
 * als JVM-Fake. Alle Methoden blockieren und laufen auf dem Render-Worker.
 */
interface MediaEngine {
    /**
     * Liest Format, Maße und Aufnahmedatum und prüft per Probe-Decode, dass
     * die Datei decodierbar ist. Wirft [ImageException].
     */
    fun probe(file: File): ImageInfo

    /**
     * Rendert [source] EXIF-gedreht auf genau [width]×[height] Pixel als JPEG
     * mit Qualität [quality] nach [target]. Das Seitenverhältnis bestimmt der Aufrufer.
     */
    fun render(
        source: File,
        target: File,
        width: Int,
        height: Int,
        quality: Int = JPEG_QUALITY,
    )

    companion object {
        const val JPEG_QUALITY = 85
    }
}

/** Erkennt Bildformate an den ersten Bytes, unabhängig von der Dateiendung. */
object ImageFormats {
    val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif")

    private val HEIF_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1", "heif")

    fun hasImageExtension(name: String) = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    fun detect(header: ByteArray): ImageFormat? {
        fun at(
            i: Int,
            vararg bytes: Int,
        ) = header.size >= i + bytes.size && bytes.indices.all { header[i + it].toInt() and 0xff == bytes[it] }

        fun ascii(
            i: Int,
            s: String,
        ) = at(i, *s.map { it.code }.toIntArray())
        return when {
            at(0, 0xFF, 0xD8, 0xFF) -> ImageFormat.JPEG
            at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> ImageFormat.PNG
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> ImageFormat.GIF
            ascii(0, "RIFF") && ascii(8, "WEBP") -> ImageFormat.WEBP
            ascii(4, "ftyp") && header.size >= 12 && String(header, 8, 4, Charsets.US_ASCII) in HEIF_BRANDS -> ImageFormat.HEIF
            else -> null
        }
    }

    fun detect(file: File): ImageFormat? =
        file.inputStream().use { input ->
            val header = ByteArray(32)
            val n = input.readNBytes(header, 0, header.size)
            detect(header.copyOf(n))
        }
}
