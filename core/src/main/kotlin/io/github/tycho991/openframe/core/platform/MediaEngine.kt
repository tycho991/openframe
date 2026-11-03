package io.github.tycho991.openframe.core.platform

import io.github.tycho991.openframe.core.readFully
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
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

/** Dimensions after EXIF rotation, and capture date and GPS position from EXIF, if present. */
data class ImageInfo(
    val format: ImageFormat,
    val width: Int,
    val height: Int,
    val takenAt: Instant?,
    val gps: GeoPoint? = null,
)

/** What [MediaEngine.readMetadata] reads from EXIF without decoding the image. */
data class ImageMetadata(
    val gps: GeoPoint?,
) {
    companion object {
        val NONE = ImageMetadata(gps = null)
    }
}

/** A position in degrees, north and east positive. */
@Serializable
data class GeoPoint(
    val lat: Double,
    val lon: Double,
) {
    companion object {
        /**
         * The position of a photo's EXIF data, or `null` if it does not count (spec `photo-library`, "Valid GPS
         * position"): outside ±90°/±180°, or 0/0, which cameras without a fix write. Every engine goes through this.
         */
        fun fromExif(
            lat: Double,
            lon: Double,
        ): GeoPoint? {
            if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            if (lat == 0.0 && lon == 0.0) return null
            return GeoPoint(lat, lon)
        }
    }
}

/**
 * Why a file with an image extension cannot be accepted. Reasons that are not [legacy] are stored as [CORRUPT] with
 * a detail, so that an older build still reads the index (see `SkippedFile`).
 */
enum class SkipReason(
    val apiName: String,
    val legacy: Boolean = false,
) {
    /** Format cannot be decoded on this device, for example HEIC before API 28. */
    UNSUPPORTED("unsupported", legacy = true),

    /** File damaged or not an image. */
    CORRUPT("corrupt", legacy = true),

    /** Over [MediaEngine.MAX_PIXELS] or with an aspect ratio over [MediaEngine.MAX_ASPECT]:1, or too big for the heap. */
    TOO_LARGE("too_large"),

    /** The decoder process died or hung on the file (spec `photo-library`, "Skipped after a decoder crash"). */
    CRASHED("crashed"),
    ;

    companion object {
        fun ofApiName(name: String): SkipReason? = entries.firstOrNull { it.apiName == name }
    }
}

class ImageException(
    val reason: SkipReason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The decoder could not be started, so the file says nothing about itself: scan, sync and backfill record nothing
 * for it and check it at their next run, and an upload answers 503 `decoder_unavailable`.
 */
class DecoderUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Image operations of the platform. In :android through `BitmapFactory`, in tests as a JVM fake. All methods block,
 * and they are called from several threads at once: the render worker, scans, syncs, the metadata backfill and
 * uploads.
 *
 * Only [ImageException] and [IOException] (among them [DecoderUnavailableException]) are thrown. An image over [MAX_PIXELS] pixels or with an aspect
 * ratio over [MAX_ASPECT]:1 is rejected as [SkipReason.TOO_LARGE] after reading its bounds (spec `photo-library`,
 * "Image size limits"); so is one whose probe runs out of memory, while running out of memory in [render] fails that
 * rendering as [SkipReason.CORRUPT].
 */
interface MediaEngine {
    /**
     * Reads format, dimensions and capture date and checks by a probe decode of at most about [PROBE_PIXELS] that
     * the file is decodable. Throws [ImageException].
     */
    fun probe(file: File): ImageInfo

    /**
     * Reads the metadata of [file] from its EXIF data only, without a probe decode, for photos already in the index
     * (spec "Metadata of photos already in the index"). A file without readable EXIF data gives [ImageMetadata.NONE].
     */
    fun readMetadata(file: File): ImageMetadata

    /**
     * Renders [region] of [source], EXIF-rotated and in rotated coordinates, to exactly [width]×[height]
     * pixels as JPEG with quality [quality] to [target]. The caller determines the aspect ratio.
     */
    fun render(
        source: File,
        target: File,
        width: Int,
        height: Int,
        region: Region = Region.FULL,
        quality: Int = JPEG_QUALITY,
    )

    companion object {
        const val JPEG_QUALITY = 85

        /** 100 megapixels: a 48 MP photo passes, a 200 MP one does not. */
        const val MAX_PIXELS = 100_000_000L
        const val MAX_ASPECT = 32

        /** About what a probe decodes at most. */
        const val PROBE_PIXELS = 1_000_000L

        /** Throws [SkipReason.TOO_LARGE] for an image of [width]×[height] over the limits. */
        fun checkLimits(
            width: Int,
            height: Int,
        ) {
            val long = maxOf(width, height).toLong()
            val short = minOf(width, height).toLong()
            if (width.toLong() * height > MAX_PIXELS) {
                throw ImageException(SkipReason.TOO_LARGE, "$width×$height is over ${MAX_PIXELS / 1_000_000} megapixels")
            }
            if (long > short * MAX_ASPECT) throw ImageException(SkipReason.TOO_LARGE, "$width×$height is over $MAX_ASPECT:1")
        }
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
