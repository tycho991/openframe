package io.github.tycho991.openframe.core.platform

import io.github.tycho991.openframe.core.readFully
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

/** Dimensions after EXIF rotation and capture date from EXIF, if present. */
data class ImageInfo(
    val format: ImageFormat,
    val width: Int,
    val height: Int,
    val takenAt: Instant?,
)

/** Why a file with an image extension cannot be accepted. */
enum class SkipReason(
    val apiName: String,
) {
    /** Format cannot be decoded on this device, for example HEIC before API 28. */
    UNSUPPORTED("unsupported"),

    /** File damaged or not an image. */
    CORRUPT("corrupt"),
}

class ImageException(
    val reason: SkipReason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Image operations of the platform. In :android through `BitmapFactory`, in tests
 * as a JVM fake. All methods block and run on the render worker.
 */
interface MediaEngine {
    /**
     * Reads format, dimensions and capture date and checks by a probe decode that
     * the file is decodable. Throws [ImageException].
     */
    fun probe(file: File): ImageInfo

    /**
     * Renders [source] EXIF-rotated to exactly [width]×[height] pixels as JPEG
     * with quality [quality] to [target]. The caller determines the aspect ratio.
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

/** Recognizes image formats by their first bytes, independent of the file extension. */
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
            val n = input.readFully(header)
            detect(header.copyOf(n))
        }
}
