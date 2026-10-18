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
 * [MediaEngine] mit `BitmapFactory`. Decodiert in zwei Durchgängen: erst nur
 * die Maße, dann mit `inSampleSize` so klein wie möglich, damit auch Fotos
 * mit 48 MP auf Geräten mit wenig Heap durchgehen. Drehung aus der
 * Framework-Klasse `ExifInterface`, nicht aus AndroidX (minSdk 21).
 */
class AndroidMediaEngine(
    private val sdkInt: Int = android.os.Build.VERSION.SDK_INT,
) : MediaEngine {
    override fun probe(file: File): ImageInfo {
        val format = ImageFormats.detect(file) ?: throw ImageException(SkipReason.CORRUPT, "Kein bekanntes Bildformat")
        // HEIC decodiert BitmapFactory erst ab API 28.
        if (format == ImageFormat.HEIF && sdkInt < HEIF_MIN_SDK) throw ImageException(SkipReason.UNSUPPORTED, "HEIC erst ab Android 9")
        val bounds = bounds(file)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ImageException(if (format == ImageFormat.HEIF) SkipReason.UNSUPPORTED else SkipReason.CORRUPT, "Nicht decodierbar")
        }
        // Probe-Decode stark verkleinert: erkennt beschädigte Dateien und fehlende Decoder, ohne viel Speicher.
        val probe =
            BitmapFactory.decodeFile(
                file.path,
                BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, PROBE_EDGE, PROBE_EDGE) },
            ) ?: throw ImageException(if (format == ImageFormat.HEIF) SkipReason.UNSUPPORTED else SkipReason.CORRUPT, "Nicht decodierbar")
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
        // Zielmaße im gespeicherten, ungedrehten Bild.
        val rawW = if (swap) height else width
        val rawH = if (swap) width else height
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, rawW, rawH)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        val decoded = BitmapFactory.decodeFile(source.path, options) ?: throw ImageException(SkipReason.CORRUPT, "Nicht decodierbar")
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val matrix = orientationMatrix(orientation, decoded.width, decoded.height)
            // Auf die Zielgröße skalieren, nach der Drehung.
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
                    throw ImageException(SkipReason.CORRUPT, "JPEG nicht schreibbar")
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
        null.also { }
            ?: parseExifDate(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME))

    companion object {
        private const val HEIF_MIN_SDK = 28
        private const val PROBE_EDGE = 64

        /** Größte Zweierpotenz, mit der das Bild noch mindestens [reqW]×[reqH] groß bleibt. */
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

        /** EXIF-Datum ohne Zeitzone, als Ortszeit des Geräts. */
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
