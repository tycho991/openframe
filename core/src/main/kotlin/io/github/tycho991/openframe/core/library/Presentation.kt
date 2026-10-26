package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.settings.FillMode
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A photo's own section: the focus point [x], [y] as fractions of width and height after EXIF
 * rotation, and a [zoom] relative to `fill` (1 fills the screen, below 1 borders, above 1 a detail).
 */
@Serializable
data class Crop(
    val x: Double,
    val y: Double,
    val zoom: Double,
) {
    /** Errors per field (`crop.x`, `crop.y`, `crop.zoom`), empty for a valid section. */
    fun errors(): Map<String, String> =
        buildMap {
            if (!(x in 0.0..1.0)) put("crop.x", "0 to 1")
            if (!(y in 0.0..1.0)) put("crop.y", "0 to 1")
            if (!(zoom > 0 && zoom <= Presentation.MAX_ZOOM)) put("crop.zoom", "Greater than 0, at most ${Presentation.MAX_ZOOM.toInt()}")
        }

    companion object {
        private const val ASPECT_TOLERANCE = 0.01

        /**
         * The section of [previous] for a new version of the file with [width]×[height]: kept if the aspect
         * ratio is the same within 1 %, else dropped, because its focus point would refer to another picture.
         */
        fun carriedOver(
            previous: PhotoEntry?,
            width: Int,
            height: Int,
        ): Crop? {
            val crop = previous?.crop ?: return null
            val before = previous.width.toLong() * height
            val after = width.toLong() * previous.height
            return crop.takeIf { abs(before - after) <= ASPECT_TOLERANCE * before }
        }
    }
}

/**
 * The part of a photo the frame shows: the window with its top left corner [x], [y] and its size as
 * fractions of the photo, the [zoom] it is shown with, and the size of the rendered display image.
 * The display shows that image centered on black, which gives the borders.
 */
data class Presentation(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val zoom: Double,
    val output: PixelSize,
) {
    /** The window rounded to 1/10,000 per coordinate, for the file name and the display URL. */
    val key: String
        get() = listOf(x, y, width, height).joinToString("_") { (it * KEY_UNITS).roundToInt().toString() }

    companion object {
        /** `auto` crops at most this share of the photo in the direction in which it does not fit the screen. */
        const val AUTO_MAX_CROP = 0.25

        const val MAX_ZOOM = 4.0
        private const val KEY_UNITS = 10_000

        /**
         * The zooms a photo of [width]×[height] can be shown with on [screen]: from fully visible to one
         * original pixel per screen pixel, at most [MAX_ZOOM]; a photo smaller than the screen allows 1.
         */
        fun zoomRange(
            width: Int,
            height: Int,
            screen: PixelSize,
        ): ClosedFloatingPointRange<Double> {
            val cover = cover(width, height, screen)
            val fit = min(screen.width.toDouble() / width, screen.height.toDouble() / height)
            return fit / cover..max(1.0, min(MAX_ZOOM, 1 / cover))
        }

        /**
         * What the frame shows of a photo of [width]×[height] (after EXIF rotation) on [screen]: with
         * [crop] its section, the zoom clamped to [zoomRange]; without, the preset of [fillMode] around
         * the center. The window is moved inward at the photo's edges, and centered where it is the whole
         * photo. The output is at most the screen and never more than the window's original pixels.
         */
        fun of(
            width: Int,
            height: Int,
            screen: PixelSize,
            crop: Crop?,
            fillMode: FillMode,
        ): Presentation {
            val range = zoomRange(width, height, screen)
            val zoom =
                crop?.zoom?.coerceIn(range)
                    ?: when (fillMode) {
                        FillMode.FILL -> 1.0
                        FillMode.FIT -> range.start
                        FillMode.AUTO -> min(1.0, range.start / (1 - AUTO_MAX_CROP))
                    }
            val scale = cover(width, height, screen) * zoom
            val w = fraction(screen.width / (scale * width))
            val h = fraction(screen.height / (scale * height))
            val cx = (crop?.x ?: 0.5).coerceIn(w / 2, 1 - w / 2)
            val cy = (crop?.y ?: 0.5).coerceIn(h / 2, 1 - h / 2)
            val k = min(scale, 1.0)
            return Presentation(
                x = cx - w / 2,
                y = cy - h / 2,
                width = w,
                height = h,
                zoom = zoom,
                output =
                    PixelSize(
                        (w * width * k).roundToInt().coerceAtLeast(1),
                        (h * height * k).roundToInt().coerceAtLeast(1),
                    ),
            )
        }

        /** At most 1, and exactly 1 where only rounding (`cover · fit / cover`) keeps it below, so the whole photo stays whole. */
        private fun fraction(value: Double) = if (value >= 1 - EPSILON) 1.0 else value

        private const val EPSILON = 1e-9

        private fun cover(
            width: Int,
            height: Int,
            screen: PixelSize,
        ) = max(screen.width.toDouble() / width, screen.height.toDouble() / height)
    }
}
