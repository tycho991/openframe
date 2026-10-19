package io.github.tycho991.openframe.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import io.github.tycho991.openframe.core.platform.ImageException
import io.github.tycho991.openframe.core.platform.ImageFormat
import io.github.tycho991.openframe.core.platform.ImageFormats
import io.github.tycho991.openframe.core.platform.ImageInfo
import io.github.tycho991.openframe.core.platform.MediaEngine
import io.github.tycho991.openframe.core.platform.SkipReason
import java.io.File
import java.io.FileOutputStream
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * [MediaEngine] with `BitmapFactory`. Decodes in two passes: first only
 * the dimensions, then with `inSampleSize` as small as possible so that even photos
 * with 48 MP get through on devices with little heap. Rotation from the
 * framework class `ExifInterface`, not from AndroidX (minSdk 21).
 */
class AndroidMediaEngine(
    private val sdkInt: Int = android.os.Build.VERSION.SDK_INT,
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
        val swap = orientation in 5..8
        return ImageInfo(
            format = format,
            width = if (swap) bounds.outHeight else bounds.outWidth,
            height = if (swap) bounds.outWidth else bounds.outHeight,
            takenAt = exif?.let(::takenAt),
        )
    }

    override fun render(
        source: File,
        target: File,
        width: Int,
        height: Int,
        quality: Int,
    ) {
        val bounds = bounds(source)
        val orientation = exif(source)?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) ?: 1
        val swap = orientation in 5..8
        // Target dimensions in the stored, unrotated image.
        val rawW = if (swap) height else width
        val rawH = if (swap) width else height
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, rawW, rawH)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        val decoded = BitmapFactory.decodeFile(source.path, options) ?: throw ImageException(SkipReason.CORRUPT, "Not decodable")
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

    private fun bounds(file: File): BitmapFactory.Options =
        BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.path, this)
        }

    private fun exif(file: File): ExifInterface? = runCatching { ExifInterface(file.path) }.getOrNull()

    private fun takenAt(exif: ExifInterface): java.time.Instant? =
        parseExifDate(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME))

    companion object {
        private const val HEIF_MIN_SDK = 28
        private const val PROBE_EDGE = 64

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
