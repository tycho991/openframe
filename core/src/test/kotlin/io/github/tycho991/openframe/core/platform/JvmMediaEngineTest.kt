package io.github.tycho991.openframe.core.platform

import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import java.time.Instant
import javax.imageio.ImageIO

class JvmMediaEngineTest {
    private val engine = JvmMediaEngine()

    @TempDir
    lateinit var dir: File

    @Test
    fun `Dimensions after rotation`() {
        assertEquals(
            ImageInfo(ImageFormat.JPEG, 400, 300, Instant.parse("2024-07-14T12:30:00Z")),
            engine.probe(Fixtures.file("landscape.jpg")),
        )
        assertEquals(300 to 400, engine.probe(Fixtures.file("rotated6.jpg")).let { it.width to it.height })
        assertEquals(300 to 400, engine.probe(Fixtures.file("rotated8.jpg")).let { it.width to it.height })
        assertNull(engine.probe(Fixtures.file("rotated8.jpg")).takenAt)
    }

    @Test
    fun `Further formats`() {
        assertEquals(ImageFormat.PNG to (200 to 100), engine.probe(Fixtures.file("image.png")).let { it.format to (it.width to it.height) })
        assertEquals(
            ImageFormat.WEBP to (160 to 120),
            engine.probe(Fixtures.file("image.webp")).let { it.format to (it.width to it.height) },
        )
        assertEquals(ImageFormat.GIF to (100 to 80), engine.probe(Fixtures.file("anim.gif")).let { it.format to (it.width to it.height) })
    }

    @Test
    fun `Broken, HEIC and not an image file`() {
        assertEquals(SkipReason.CORRUPT, assertThrows<ImageException> { engine.probe(Fixtures.file("broken.jpg")) }.reason)
        assertEquals(SkipReason.UNSUPPORTED, assertThrows<ImageException> { engine.probe(Fixtures.file("photo.heic")) }.reason)
        assertEquals(SkipReason.CORRUPT, assertThrows<ImageException> { engine.probe(Fixtures.file("note.txt")) }.reason)
    }

    @Test
    fun `Rendering rotates by EXIF`() {
        val out = File(dir, "out.jpg")
        engine.render(Fixtures.file("rotated6.jpg"), out, 150, 200)
        val img = ImageIO.read(out)
        assertEquals(150 to 200, img.width to img.height)
        // The red marker was at the top left in the stored image, after a 90° clockwise rotation it is at the top right.
        val topRight = img.getRGB(140, 10)
        val red = (topRight shr 16) and 0xff
        val green = (topRight shr 8) and 0xff
        assertEquals(true, red > 200 && green < 80, "top right red, was ${Integer.toHexString(topRight)}")
    }

    @Test
    fun `Rendering a region renders only that part`() {
        // Four quadrants in distinct colours: red, green / blue, white.
        val source = File(dir, "quadrants.png")
        val image = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 200) {
            for (y in 0 until 100) {
                val color =
                    when {
                        x < 100 && y < 50 -> 0xff0000
                        y < 50 -> 0x00ff00
                        x < 100 -> 0x0000ff
                        else -> 0xffffff
                    }
                image.setRGB(x, y, color)
            }
        }
        ImageIO.write(image, "png", source)
        val out = File(dir, "out.jpg")
        engine.render(source, out, 80, 40, Region(0.0, 0.0, 0.5, 0.5))
        val img = ImageIO.read(out)
        assertEquals(80 to 40, img.width to img.height)
        for ((x, y) in listOf(2 to 2, 77 to 2, 2 to 37, 77 to 37, 40 to 20)) {
            val rgb = img.getRGB(x, y)
            val red = (rgb shr 16) and 0xff
            val green = (rgb shr 8) and 0xff
            val blue = rgb and 0xff
            assertEquals(true, red > 200 && green < 60 && blue < 60, "red at $x,$y, was ${Integer.toHexString(rgb)}")
        }
        assertEquals(listOf(Region(0.0, 0.0, 0.5, 0.5)), engine.regions)
    }

    @Test
    fun `Format detection by the first bytes`() {
        assertEquals(ImageFormat.JPEG, ImageFormats.detect(Fixtures.file("landscape.jpg")))
        assertEquals(ImageFormat.HEIF, ImageFormats.detect(Fixtures.file("photo.heic")))
        assertNull(ImageFormats.detect(Fixtures.file("note.txt")))
        assertEquals(true, ImageFormats.hasImageExtension("Urlaub/IMG_1.JPEG"))
        assertEquals(false, ImageFormats.hasImageExtension("clip.mp4"))
    }
}
