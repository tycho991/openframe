package io.github.tycho991.openframe.android

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import io.github.tycho991.openframe.core.library.Crop
import io.github.tycho991.openframe.core.library.PixelSize
import io.github.tycho991.openframe.core.library.Presentation
import io.github.tycho991.openframe.core.platform.ExifOrientation
import io.github.tycho991.openframe.core.platform.ImageException
import io.github.tycho991.openframe.core.platform.ImageFormat
import io.github.tycho991.openframe.core.platform.Region
import io.github.tycho991.openframe.core.platform.SkipReason
import io.github.tycho991.openframe.core.settings.FillMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.imageio.ImageIO

/** The image pipeline with real decoders through Robolectric, with the test images from :core. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class AndroidMediaEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val engine = AndroidMediaEngine(sdkInt = 28)

    private fun fixture(name: String): File {
        val stream = requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "Fixture $name is missing" }
        return File(tmp.root, name).also { f -> stream.use { s -> f.outputStream().use { s.copyTo(it) } } }
    }

    private fun skipReason(block: () -> Unit): SkipReason {
        try {
            block()
        } catch (e: ImageException) {
            return e.reason
        }
        fail("ImageException expected")
        throw AssertionError()
    }

    @Test
    fun dimensionsAfterRotationAndCaptureDate() {
        val landscape = engine.probe(fixture("landscape.jpg"))
        assertEquals(ImageFormat.JPEG, landscape.format)
        assertEquals(400 to 300, landscape.width to landscape.height)
        val expected =
            SimpleDateFormat(
                "yyyy:MM:dd HH:mm:ss",
                Locale.US,
            ).apply { timeZone = TimeZone.getDefault() }.parse("2024:07:14 12:30:00")!!
        assertEquals(expected.time, landscape.takenAt?.toEpochMilli())
        assertEquals(300 to 400, engine.probe(fixture("rotated6.jpg")).let { it.width to it.height })
        assertEquals(300 to 400, engine.probe(fixture("rotated8.jpg")).let { it.width to it.height })
        assertNull(engine.probe(fixture("rotated8.jpg")).takenAt)
    }

    @Test
    fun furtherFormatsAndGifAsStillImage() {
        assertEquals(200 to 100, engine.probe(fixture("image.png")).let { it.width to it.height })
        assertEquals(160 to 120, engine.probe(fixture("image.webp")).let { it.width to it.height })
        val gif = engine.probe(fixture("anim.gif"))
        assertEquals(ImageFormat.GIF, gif.format)
        val out = File(tmp.root, "gif.jpg")
        engine.render(fixture("anim.gif"), out, 100, 80)
        assertEquals(100, BitmapFactory.decodeFile(out.path).width)
    }

    @Test
    fun brokenFileAndHeicOnOldDevice() {
        assertEquals(SkipReason.CORRUPT, skipReason { engine.probe(fixture("broken.jpg")) })
        assertEquals(SkipReason.CORRUPT, skipReason { engine.probe(fixture("note.txt")) })
        assertEquals(SkipReason.UNSUPPORTED, skipReason { AndroidMediaEngine(sdkInt = 23).probe(fixture("photo.heic")) })
    }

    @Test
    fun renderingRotatesByExifAndWritesJpeg() {
        val out = File(tmp.root, "out.jpg")
        engine.render(fixture("rotated6.jpg"), out, 150, 200)
        val bmp = BitmapFactory.decodeFile(out.path)
        assertEquals(150 to 200, bmp.width to bmp.height)
        // The red marker at the top left in the stored image is at the top right after 90° clockwise.
        val p = bmp.getPixel(140, 10)
        assertTrue("top right red, was ${Integer.toHexString(p)}", Color.red(p) > 200 && Color.green(p) < 80)
        val bytes = out.readBytes()
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])
    }

    @Test
    fun regionOfAJpegWithExifOrientation6() {
        // Robolectric's region decoder returns empty bitmaps, so the pixels are checked on the fallback, which maps the
        // region the same way; the region decoder itself is checked by its size below and on the devices.
        val pixels = AndroidMediaEngine(sdkInt = 28, regionDecoding = false)

        fun red(p: Int) = Color.red(p) > 200 && Color.green(p) < 80
        // The marker is the top left quarter of the stored 400×300 image, so in the upright 300×400 photo it is at
        // x 225 to 300, y 0 to 100: in the top right quarter, and not in the top left one.
        val right = File(tmp.root, "right.jpg")
        pixels.render(fixture("rotated6.jpg"), right, 150, 200, Region(0.5, 0.0, 0.5, 0.5))
        val r = BitmapFactory.decodeFile(right.path)
        assertEquals(150 to 200, r.width to r.height)
        assertTrue("marker, was ${Integer.toHexString(r.getPixel(140, 10))}", red(r.getPixel(140, 10)))
        assertTrue("marker, was ${Integer.toHexString(r.getPixel(80, 90))}", red(r.getPixel(80, 90)))
        assertTrue("no marker, was ${Integer.toHexString(r.getPixel(60, 10))}", !red(r.getPixel(60, 10)))
        assertTrue("no marker, was ${Integer.toHexString(r.getPixel(140, 110))}", !red(r.getPixel(140, 110)))
        val left = File(tmp.root, "left.jpg")
        pixels.render(fixture("rotated6.jpg"), left, 150, 200, Region(0.0, 0.0, 0.5, 0.5))
        val l = BitmapFactory.decodeFile(left.path)
        assertTrue("no marker in the top left quarter", (0 until 150 step 10).none { x -> red(l.getPixel(x, 10)) })
        // The top right quarter of the upright photo is the top left quarter of the stored one, 200×150.
        val region = ExifOrientation.toStored(6, Region(0.5, 0.0, 0.5, 0.5))
        assertEquals(Region(0.0, 0.0, 0.5, 0.5), region)
        val stored = engine.decodeStored(fixture("rotated6.jpg"), region, 200, 150)
        assertEquals(200 to 150, stored.width to stored.height)
    }

    @Test
    fun regionOfAGifFallsBackToAFullDecode() {
        val out = File(tmp.root, "gif.jpg")
        engine.render(fixture("anim.gif"), out, 50, 40, Region(0.0, 0.0, 0.5, 0.5))
        assertEquals(50 to 40, BitmapFactory.decodeFile(out.path).let { it.width to it.height })
        // 100×80, the top left quarter.
        val part = engine.decodeStored(fixture("anim.gif"), Region(0.0, 0.0, 0.5, 0.5), 50, 40)
        assertEquals(50 to 40, part.width to part.height)
    }

    @Test
    fun aDetailOf48MegapixelsDecodesOnlyItsRegion() {
        // A grey JPEG of 8000×6000, written with ImageIO so that the test needs no 192 MB bitmap.
        val file = File(tmp.root, "big.jpg")
        val image = BufferedImage(8000, 6000, BufferedImage.TYPE_BYTE_GRAY)
        ImageIO.write(image, "jpg", file)
        val p = Presentation.of(8000, 6000, PixelSize(1920, 1200), Crop(0.5, 0.5, 4.0), FillMode.FILL)
        assertEquals(4.0, p.zoom, 1e-9)
        val decoded = engine.decodeStored(file, Region(p.x, p.y, p.width, p.height), p.output.width, p.output.height)
        assertTrue("${decoded.width}×${decoded.height}", decoded.width <= 2 * p.output.width && decoded.height <= 2 * p.output.height)
        assertTrue("${decoded.width}×${decoded.height}", decoded.width >= p.output.width && decoded.height >= p.output.height)
        val out = File(tmp.root, "out.jpg")
        engine.render(file, out, p.output.width, p.output.height, Region(p.x, p.y, p.width, p.height))
        assertEquals(1920 to 1200, BitmapFactory.decodeFile(out.path).let { it.width to it.height })
    }

    @Test
    fun regionInPixels() {
        assertEquals(Rect(0, 0, 400, 300), AndroidMediaEngine.pixels(Region.FULL, 400, 300))
        assertEquals(Rect(100, 75, 300, 225), AndroidMediaEngine.pixels(Region(0.25, 0.25, 0.5, 0.5), 400, 300))
        // Corner and size rounded each on its own, at least one pixel.
        assertEquals(Rect(33, 0, 66, 1), AndroidMediaEngine.pixels(Region(1.0 / 12, 0.0, 1.0 / 12, 0.0001), 400, 3))
        // A section at the largest zoom shows original pixels 1:1: 6000×4000 on 1920×1200 is a window of exactly
        // 1920×1200 pixels, whether its corner comes out of the doubles as 0.5399… (Lenovo, 26 Oct 2026) or lies
        // between two pixels.
        for ((x, corner) in listOf(0.7 to 3240, 0.7001 to 3241)) {
            val p = Presentation.of(6000, 4000, PixelSize(1920, 1200), Crop(x, 0.6, 4.0), FillMode.FILL)
            val rect = AndroidMediaEngine.pixels(Region(p.x, p.y, p.width, p.height), 6000, 4000)
            assertEquals(1920 to 1200, rect.width() to rect.height())
            assertEquals(corner, rect.left)
        }
    }

    @Test
    fun inSampleSizeInTwoPasses() {
        assertEquals(1, AndroidMediaEngine.sampleSize(400, 300, 400, 300))
        assertEquals(4, AndroidMediaEngine.sampleSize(8000, 6000, 1920, 1200))
        assertEquals(2, AndroidMediaEngine.sampleSize(8000, 6000, 2560, 1920))
    }
}
