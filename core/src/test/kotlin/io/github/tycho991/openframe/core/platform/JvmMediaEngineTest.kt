package io.github.tycho991.openframe.core.platform

import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import javax.imageio.ImageIO

class JvmMediaEngineTest {
    private val engine = JvmMediaEngine()

    @TempDir
    lateinit var dir: File

    @Test
    fun `Masse nach Drehung`() {
        assertEquals(
            ImageInfo(ImageFormat.JPEG, 400, 300, Instant.parse("2024-07-14T12:30:00Z")),
            engine.probe(Fixtures.file("landscape.jpg")),
        )
        assertEquals(300 to 400, engine.probe(Fixtures.file("rotated6.jpg")).let { it.width to it.height })
        assertEquals(300 to 400, engine.probe(Fixtures.file("rotated8.jpg")).let { it.width to it.height })
        assertNull(engine.probe(Fixtures.file("rotated8.jpg")).takenAt)
    }

    @Test
    fun `Weitere Formate`() {
        assertEquals(ImageFormat.PNG to (200 to 100), engine.probe(Fixtures.file("image.png")).let { it.format to (it.width to it.height) })
        assertEquals(
            ImageFormat.WEBP to (160 to 120),
            engine.probe(Fixtures.file("image.webp")).let { it.format to (it.width to it.height) },
        )
        assertEquals(ImageFormat.GIF to (100 to 80), engine.probe(Fixtures.file("anim.gif")).let { it.format to (it.width to it.height) })
    }

    @Test
    fun `Defekt, HEIC und keine Bilddatei`() {
        assertEquals(SkipReason.CORRUPT, assertThrows<ImageException> { engine.probe(Fixtures.file("broken.jpg")) }.reason)
        assertEquals(SkipReason.UNSUPPORTED, assertThrows<ImageException> { engine.probe(Fixtures.file("photo.heic")) }.reason)
        assertEquals(SkipReason.CORRUPT, assertThrows<ImageException> { engine.probe(Fixtures.file("notiz.txt")) }.reason)
    }

    @Test
    fun `Rendern dreht nach EXIF`() {
        val out = File(dir, "out.jpg")
        engine.render(Fixtures.file("rotated6.jpg"), out, 150, 200)
        val img = ImageIO.read(out)
        assertEquals(150 to 200, img.width to img.height)
        // Die rote Markierung lag oben links im gespeicherten Bild, nach Drehung um 90° im Uhrzeigersinn oben rechts.
        val topRight = img.getRGB(140, 10)
        val red = (topRight shr 16) and 0xff
        val green = (topRight shr 8) and 0xff
        assertEquals(true, red > 200 && green < 80, "oben rechts rot, war ${Integer.toHexString(topRight)}")
    }

    @Test
    fun `Formaterkennung an den ersten Bytes`() {
        assertEquals(ImageFormat.JPEG, ImageFormats.detect(Fixtures.file("landscape.jpg")))
        assertEquals(ImageFormat.HEIF, ImageFormats.detect(Fixtures.file("photo.heic")))
        assertNull(ImageFormats.detect(Fixtures.file("notiz.txt")))
        assertEquals(true, ImageFormats.hasImageExtension("Urlaub/IMG_1.JPEG"))
        assertEquals(false, ImageFormats.hasImageExtension("clip.mp4"))
    }
}
