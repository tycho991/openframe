package io.github.tycho991.openframe.android

import android.graphics.BitmapFactory
import android.graphics.Color
import io.github.tycho991.openframe.core.platform.ImageException
import io.github.tycho991.openframe.core.platform.ImageFormat
import io.github.tycho991.openframe.core.platform.SkipReason
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Die Bildpipeline mit echten Decodern über Robolectric, mit den Testbildern aus :core. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class AndroidMediaEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val engine = AndroidMediaEngine(sdkInt = 28)

    private fun fixture(name: String): File {
        val stream = requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "Fixture $name fehlt" }
        return File(tmp.root, name).also { f -> stream.use { s -> f.outputStream().use { s.copyTo(it) } } }
    }

    private fun skipReason(block: () -> Unit): SkipReason {
        try {
            block()
        } catch (e: ImageException) {
            return e.reason
        }
        fail("ImageException erwartet")
        throw AssertionError()
    }

    @Test
    fun masseNachDrehungUndAufnahmedatum() {
        val landscape = engine.probe(fixture("landscape.jpg"))
        assertEquals(ImageFormat.JPEG, landscape.format)
        assertEquals(400 to 300, landscape.width to landscape.height)
        val expected = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getDefault() }.parse("2024:07:14 12:30:00")!!
        assertEquals(expected.time, landscape.takenAt?.toEpochMilli())
        assertEquals(300 to 400, engine.probe(fixture("rotated6.jpg")).let { it.width to it.height })
        assertEquals(300 to 400, engine.probe(fixture("rotated8.jpg")).let { it.width to it.height })
        assertNull(engine.probe(fixture("rotated8.jpg")).takenAt)
    }

    @Test
    fun weitereFormateUndGifAlsStandbild() {
        assertEquals(200 to 100, engine.probe(fixture("image.png")).let { it.width to it.height })
        assertEquals(160 to 120, engine.probe(fixture("image.webp")).let { it.width to it.height })
        val gif = engine.probe(fixture("anim.gif"))
        assertEquals(ImageFormat.GIF, gif.format)
        val out = File(tmp.root, "gif.jpg")
        engine.render(fixture("anim.gif"), out, 100, 80)
        assertEquals(100, BitmapFactory.decodeFile(out.path).width)
    }

    @Test
    fun defekteDateiUndHeicAufAltemGeraet() {
        assertEquals(SkipReason.CORRUPT, skipReason { engine.probe(fixture("broken.jpg")) })
        assertEquals(SkipReason.CORRUPT, skipReason { engine.probe(fixture("notiz.txt")) })
        assertEquals(SkipReason.UNSUPPORTED, skipReason { AndroidMediaEngine(sdkInt = 23).probe(fixture("photo.heic")) })
    }

    @Test
    fun rendernDrehtNachExifUndSchreibtJpeg() {
        val out = File(tmp.root, "out.jpg")
        engine.render(fixture("rotated6.jpg"), out, 150, 200)
        val bmp = BitmapFactory.decodeFile(out.path)
        assertEquals(150 to 200, bmp.width to bmp.height)
        // Rote Markierung oben links im gespeicherten Bild liegt nach 90° im Uhrzeigersinn oben rechts.
        val p = bmp.getPixel(140, 10)
        assertTrue("oben rechts rot, war ${Integer.toHexString(p)}", Color.red(p) > 200 && Color.green(p) < 80)
        val bytes = out.readBytes()
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])
    }

    @Test
    fun inSampleSizeInZweiDurchgaengen() {
        assertEquals(1, AndroidMediaEngine.sampleSize(400, 300, 400, 300))
        assertEquals(4, AndroidMediaEngine.sampleSize(8000, 6000, 1920, 1200))
        assertEquals(2, AndroidMediaEngine.sampleSize(8000, 6000, 2560, 1920))
    }
}
