package io.github.tycho991.openframe.core.testing

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import io.github.tycho991.openframe.core.platform.GeoPoint
import io.github.tycho991.openframe.core.platform.ImageException
import io.github.tycho991.openframe.core.platform.ImageFormat
import io.github.tycho991.openframe.core.platform.ImageFormats
import io.github.tycho991.openframe.core.platform.ImageInfo
import io.github.tycho991.openframe.core.platform.ImageMetadata
import io.github.tycho991.openframe.core.platform.MediaEngine
import io.github.tycho991.openframe.core.platform.Region
import io.github.tycho991.openframe.core.platform.SkipReason
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import java.time.ZoneOffset
import java.util.Collections
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.roundToInt

/**
 * [MediaEngine] on the JVM with ImageIO (WebP through TwelveMonkeys) and
 * metadata-extractor. Counts how often files are opened and decoded.
 */
class JvmMediaEngine(
    private val supportsHeic: Boolean = false,
) : MediaEngine {
    val probes = AtomicInteger()
    val renders = AtomicInteger()
    val metadataReads = AtomicInteger()

    /** Every decode of image data, by a probe or a render. */
    val decodes = AtomicInteger()

    /** The region of every render, in order. */
    val regions: MutableList<Region> = Collections.synchronizedList(mutableListOf())

    /** What a probe or a render of a file throws instead of decoding it, for example an [OutOfMemoryError]. */
    @Volatile var probeFailure: (File) -> Throwable? = { null }

    @Volatile var renderFailure: (File) -> Throwable? = { null }

    @Volatile var metadataFailure: (File) -> Throwable? = { null }

    override fun probe(file: File): ImageInfo {
        probes.incrementAndGet()
        probeFailure(file)?.let { throw it }
        val format = ImageFormats.detect(file) ?: throw ImageException(SkipReason.CORRUPT, "No known image format")
        if (format == ImageFormat.HEIF && !supportsHeic) throw ImageException(SkipReason.UNSUPPORTED, "HEIC not decodable")
        bounds(file)?.let { (w, h) -> MediaEngine.checkLimits(w, h) }
        val orientation = orientation(file)
        val image = decode(file)
        val swap = orientation in 5..8
        return ImageInfo(
            format = format,
            width = if (swap) image.height else image.width,
            height = if (swap) image.width else image.height,
            takenAt = takenAt(file),
            gps = gps(file),
        )
    }

    override fun readMetadata(file: File): ImageMetadata {
        metadataReads.incrementAndGet()
        metadataFailure(file)?.let { throw it }
        return ImageMetadata(gps = gps(file))
    }

    override fun render(
        source: File,
        target: File,
        width: Int,
        height: Int,
        region: Region,
        quality: Int,
    ) {
        renders.incrementAndGet()
        regions += region
        renderFailure(source)?.let { throw it }
        val rotated = rotate(decode(source), orientation(source))
        val x = (region.x * rotated.width).roundToInt().coerceIn(0, rotated.width - 1)
        val y = (region.y * rotated.height).roundToInt().coerceIn(0, rotated.height - 1)
        val part =
            rotated.getSubimage(
                x,
                y,
                (region.width * rotated.width).roundToInt().coerceIn(1, rotated.width - x),
                (region.height * rotated.height).roundToInt().coerceIn(1, rotated.height - y),
            )
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(part, 0, 0, width, height, null)
            dispose()
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        target.parentFile.mkdirs()
        ImageIO.createImageOutputStream(target).use { stream ->
            writer.output = stream
            val param =
                writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality / 100f
                }
            writer.write(null, IIOImage(out, null, null), param)
            writer.dispose()
        }
    }

    /** Width and height from the header, without decoding, as `BitmapFactory` reads the bounds first. */
    private fun bounds(file: File): Pair<Int, Int>? =
        runCatching {
            ImageIO.createImageInputStream(file).use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
                try {
                    reader.input = input
                    reader.getWidth(0) to reader.getHeight(0)
                } finally {
                    reader.dispose()
                }
            }
        }.getOrNull()

    private fun decode(file: File): BufferedImage =
        try {
            decodes.incrementAndGet()
            ImageIO.read(file) ?: throw ImageException(SkipReason.CORRUPT, "Not decodable")
        } catch (e: ImageException) {
            throw e
        } catch (e: Exception) {
            throw ImageException(SkipReason.CORRUPT, "Not decodable: ${e.message}", e)
        }

    private fun orientation(file: File): Int =
        runCatching {
            ImageMetadataReader
                .readMetadata(file)
                .getFirstDirectoryOfType(ExifIFD0Directory::class.java)
                ?.takeIf { it.containsTag(ExifIFD0Directory.TAG_ORIENTATION) }
                ?.getInt(ExifIFD0Directory.TAG_ORIENTATION)
        }.getOrNull() ?: 1

    private fun takenAt(file: File) =
        runCatching {
            ImageMetadataReader
                .readMetadata(file)
                .getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
                ?.getDateOriginal(TimeZone.getTimeZone(ZoneOffset.UTC))
                ?.toInstant()
        }.getOrNull()

    private fun gps(file: File): GeoPoint? =
        runCatching {
            ImageMetadataReader
                .readMetadata(file)
                .getFirstDirectoryOfType(GpsDirectory::class.java)
                ?.geoLocation
                ?.let { GeoPoint.fromExif(it.latitude, it.longitude) }
        }.getOrNull()

    private fun rotate(
        image: BufferedImage,
        orientation: Int,
    ): BufferedImage {
        if (orientation !in 2..8) return image
        val w = image.width.toDouble()
        val h = image.height.toDouble()
        val swap = orientation in 5..8
        val t = AffineTransform()
        when (orientation) {
            2 -> {
                t.translate(w, 0.0)
                t.scale(-1.0, 1.0)
            }

            3 -> {
                t.translate(w, h)
                t.rotate(Math.PI)
            }

            4 -> {
                t.translate(0.0, h)
                t.scale(1.0, -1.0)
            }

            5 -> {
                t.rotate(Math.PI / 2)
                t.scale(1.0, -1.0)
            }

            6 -> {
                t.translate(h, 0.0)
                t.rotate(Math.PI / 2)
            }

            7 -> {
                t.scale(-1.0, 1.0)
                t.translate(-h, 0.0)
                t.translate(0.0, w)
                t.rotate(3 * Math.PI / 2)
            }

            8 -> {
                t.translate(0.0, w)
                t.rotate(3 * Math.PI / 2)
            }
        }
        val out =
            BufferedImage(if (swap) image.height else image.width, if (swap) image.width else image.height, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            drawImage(image, t, null)
            dispose()
        }
        return out
    }
}
