package io.github.tycho991.openframe.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import io.github.tycho991.openframe.core.platform.ExifOrientation
import io.github.tycho991.openframe.core.platform.GeoPoint
import io.github.tycho991.openframe.core.platform.ImageException
import io.github.tycho991.openframe.core.platform.ImageFormat
import io.github.tycho991.openframe.core.platform.ImageFormats
import io.github.tycho991.openframe.core.platform.ImageInfo
import io.github.tycho991.openframe.core.platform.ImageMetadata
import io.github.tycho991.openframe.core.platform.MediaEngine
import io.github.tycho991.openframe.core.platform.Region
import io.github.tycho991.openframe.core.platform.SkipReason
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * [MediaEngine] with `BitmapFactory`. Decodes in two passes: first only
 * the dimensions, then with `inSampleSize` as small as possible so that even photos
 * with 48 MP get through on devices with little heap. A section decodes only its region
 * with `BitmapRegionDecoder`. Rotation from the framework class `ExifInterface`, not
 * from AndroidX (minSdk 21).
 */
class AndroidMediaEngine(
    private val sdkInt: Int = android.os.Build.VERSION.SDK_INT,
    /** Off in tests that check the pixels of the fallback: Robolectric's region decoder returns empty bitmaps. */
    private val regionDecoding: Boolean = true,
) : MediaEngine {
    override fun probe(file: File): ImageInfo {
        val format = ImageFormats.detect(file) ?: throw ImageException(SkipReason.CORRUPT, "No known image format")
        // BitmapFactory decodes HEIC only from API 28.
        if (format == ImageFormat.HEIF && sdkInt < HEIF_MIN_SDK) throw ImageException(SkipReason.UNSUPPORTED, "HEIC only from Android 9")
        val bounds = bounds(file)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ImageException(if (format == ImageFormat.HEIF) SkipReason.UNSUPPORTED else SkipReason.CORRUPT, "Not decodable")
        }
        // Probe decode heavily downscaled: detects damaged files and missing decoders without much memory.
        val probe =
            BitmapFactory.decodeFile(
                file.path,
                BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, PROBE_EDGE, PROBE_EDGE) },
            ) ?: throw ImageException(if (format == ImageFormat.HEIF) SkipReason.UNSUPPORTED else SkipReason.CORRUPT, "Not decodable")
        probe.recycle()
        val exif = exif(file)
        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) ?: 1
        val swap = ExifOrientation.swapsAxes(orientation)
        return ImageInfo(
            format = format,
            width = if (swap) bounds.outHeight else bounds.outWidth,
            height = if (swap) bounds.outWidth else bounds.outHeight,
            takenAt = exif?.let(::takenAt),
            gps = exif?.let(::gps),
        )
    }

    override fun readMetadata(file: File): ImageMetadata = exif(file)?.let { ImageMetadata(gps = gps(it)) } ?: ImageMetadata.NONE

    override fun render(
        source: File,
        target: File,
        width: Int,
        height: Int,
        region: Region,
        quality: Int,
    ) {
        val orientation = exif(source)?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) ?: 1
        val swap = ExifOrientation.swapsAxes(orientation)
        // Target dimensions in the stored, unrotated image.
        val rawW = if (swap) height else width
        val rawH = if (swap) width else height
        val decoded = decodeStored(source, ExifOrientation.toStored(orientation, region), rawW, rawH)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val matrix = orientationMatrix(orientation, decoded.width, decoded.height)
            // Scale to the target size, after the rotation.
            val rotatedW = if (swap) decoded.height else decoded.width
            val rotatedH = if (swap) decoded.width else decoded.height
            matrix.postScale(width.toFloat() / rotatedW, height.toFloat() / rotatedH)
            Canvas(out).drawBitmap(decoded, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { stream ->
                if (!out.compress(
                        Bitmap.CompressFormat.JPEG,
                        quality,
                        stream,
                    )
                ) {
                    throw ImageException(SkipReason.CORRUPT, "JPEG not writable")
                }
            }
        } finally {
            decoded.recycle()
            out.recycle()
        }
    }

    /**
     * [region] of the stored, unrotated image, sampled down as far as it stays at least [reqW]×[reqH]. Only the region
     * is decoded, so a detail of a 48 MP photo needs about the memory of the screen. Formats or devices without region
     * decoding (GIF, a failing decoder) get a sampled full decode and a crop instead.
     */
    internal fun decodeStored(
        file: File,
        region: Region,
        reqW: Int,
        reqH: Int,
    ): Bitmap {
        val bounds = bounds(file)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ImageException(SkipReason.CORRUPT, "Not decodable")
        val rect = pixels(region, bounds.outWidth, bounds.outHeight)
        if (!region.isFull && regionDecoding) {
            decodeRegion(file, rect, sampleSize(rect.width(), rect.height(), reqW, reqH))?.let { return it }
        }
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = fallbackSampleSize(bounds.outWidth, bounds.outHeight, region, reqW, reqH)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: throw ImageException(SkipReason.CORRUPT, "Not decodable")
        if (region.isFull) return decoded
        val part = pixels(region, decoded.width, decoded.height)
        return Bitmap.createBitmap(decoded, part.left, part.top, part.width(), part.height()).also { if (it !== decoded) decoded.recycle() }
    }

    /** `null` if the format or the device cannot decode a region, for example GIF. */
    @Suppress("DEPRECATION")
    private fun decodeRegion(
        file: File,
        rect: Rect,
        sample: Int,
    ): Bitmap? {
        val decoder =
            try {
                // newInstance(String) without the flag exists only from API 31.
                BitmapRegionDecoder.newInstance(file.path, false)
            } catch (e: IOException) {
                return null
            } ?: return null
        return try {
            decoder.decodeRegion(
                rect,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } catch (e: RuntimeException) {
            null
        } finally {
            decoder.recycle()
        }
    }

    private fun bounds(file: File): BitmapFactory.Options =
        BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.path, this)
        }

    private fun exif(file: File): ExifInterface? = runCatching { ExifInterface(file.path) }.getOrNull()

    private fun gps(exif: ExifInterface): GeoPoint? {
        val latLong = FloatArray(2)
        if (!exif.getLatLong(latLong)) return null
        return GeoPoint.fromExif(latLong[0].toDouble(), latLong[1].toDouble())
    }

    private fun takenAt(exif: ExifInterface): java.time.Instant? =
        parseExifDate(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME))

    companion object {
        private const val HEIF_MIN_SDK = 28
        private const val PROBE_EDGE = 64

        /**
         * [region] in whole pixels of an image of [width]×[height]: the corner rounded to the nearest pixel and the size
         * rounded on its own, at least one pixel. Rounding both edges outward would make a window of 1920 pixels at a
         * fractional position 1921 wide, and scaling it by that hair to 1920 blurs every fine detail at the largest zoom.
         */
        fun pixels(
            region: Region,
            width: Int,
            height: Int,
        ): Rect {
            val left = (region.x * width).roundToInt().coerceIn(0, width - 1)
            val top = (region.y * height).roundToInt().coerceIn(0, height - 1)
            val w = (region.width * width).roundToInt().coerceIn(1, width - left)
            val h = (region.height * height).roundToInt().coerceIn(1, height - top)
            return Rect(left, top, left + w, top + h)
        }

        /**
         * Sample size of the fallback that decodes the whole image of [width]×[height] for [region]: as small as keeps
         * the region at least [reqW]×[reqH], but never more than [MAX_FALLBACK_PIXELS] for the whole image. A detail at
         * zoom 4 of a 48 MP photo would otherwise be a full-size decode of 192 MB; the fallback is blurred instead.
         */
        fun fallbackSampleSize(
            width: Int,
            height: Int,
            region: Region,
            reqW: Int,
            reqH: Int,
        ): Int {
            var sample = sampleSize(width, height, ceil(reqW / region.width).toInt(), ceil(reqH / region.height).toInt())
            while ((width / sample).toLong() * (height / sample) > MAX_FALLBACK_PIXELS) sample *= 2
            return sample
        }

        /** 32 MB as ARGB_8888, the size of a 4K screen. */
        const val MAX_FALLBACK_PIXELS = 8_000_000L

        /** Largest power of two with which the image still stays at least [reqW]×[reqH] in size. */
        fun sampleSize(
            width: Int,
            height: Int,
            reqW: Int,
            reqH: Int,
        ): Int {
            var sample = 1
            while (width / (sample * 2) >= reqW && height / (sample * 2) >= reqH) sample *= 2
            return sample
        }

        /** EXIF date without time zone, as the device's local time. */
        fun parseExifDate(value: String?): java.time.Instant? {
            if (value.isNullOrBlank()) return null
            val format = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getDefault() }
            return try {
                format.parse(value.trim())?.let { java.time.Instant.ofEpochMilli(it.time) }
            } catch (e: ParseException) {
                null
            }
        }

        fun orientationMatrix(
            orientation: Int,
            width: Int,
            height: Int,
        ): Matrix {
            val m = Matrix()
            val w = width.toFloat()
            val h = height.toFloat()
            when (orientation) {
                2 -> {
                    m.setScale(-1f, 1f).also { m.postTranslate(w, 0f) }
                }

                3 -> {
                    m.setRotate(180f).also { m.postTranslate(w, h) }
                }

                4 -> {
                    m.setScale(1f, -1f).also { m.postTranslate(0f, h) }
                }

                5 -> {
                    m.setRotate(90f).also { m.postScale(-1f, 1f) }
                }

                6 -> {
                    m.setRotate(90f).also { m.postTranslate(h, 0f) }
                }

                7 -> {
                    m.setRotate(-90f).also {
                        m.postScale(-1f, 1f)
                        m.postTranslate(h, w)
                    }
                }

                8 -> {
                    m.setRotate(-90f).also { m.postTranslate(0f, w) }
                }
            }
            return m
        }
    }
}
