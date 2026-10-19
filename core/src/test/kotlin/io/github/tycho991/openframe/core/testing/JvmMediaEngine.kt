package io.github.tycho991.openframe.core.testing

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import io.github.tycho991.openframe.core.platform.ImageException
import io.github.tycho991.openframe.core.platform.ImageFormat
import io.github.tycho991.openframe.core.platform.ImageFormats
import io.github.tycho991.openframe.core.platform.ImageInfo
import io.github.tycho991.openframe.core.platform.MediaEngine
import io.github.tycho991.openframe.core.platform.SkipReason
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * [MediaEngine] on the JVM with ImageIO (WebP through TwelveMonkeys) and
 * metadata-extractor. Counts how often files are opened.
 */
class JvmMediaEngine(
    private val supportsHeic: Boolean = false,
) : MediaEngine {
    val probes = AtomicInteger()
    val renders = AtomicInteger()

    override fun probe(file: File): ImageInfo {
        probes.incrementAndGet()
        val format = ImageFormats.detect(file) ?: throw ImageException(SkipReason.CORRUPT, "No known image format")
        if (format == ImageFormat.HEIF && !supportsHeic) throw ImageException(SkipReason.UNSUPPORTED, "HEIC not decodable")
        val orientation = orientation(file)
        val image = decode(file)
        val swap = orientation in 5..8
        return ImageInfo(
            format = format,
            width = if (swap) image.height else image.width,
            height = if (swap) image.width else image.height,
            takenAt = takenAt(file),
        )
    }

    override fun render(
        source: File,
        target: File,
        width: Int,
        height: Int,
        quality: Int,
    ) {
        renders.incrementAndGet()
        val rotated = rotate(decode(source), orientation(source))
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(rotated, 0, 0, width, height, null)
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

    private fun decode(file: File): BufferedImage =
        try {
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
