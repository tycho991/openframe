package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.library.PhotoLibrary
import io.github.tycho991.openframe.core.testing.FakePlatform
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import io.github.tycho991.openframe.core.testing.TestFrame
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream

/** Spec `photo-library`: Upload. */
class UploadApiTest {
    @TempDir
    lateinit var dir: File

    private val platform = FakePlatform()
    private val frame by lazy { TestFrame(dir, JvmMediaEngine(supportsHeic = false), platform) }
    private val jpeg = Fixtures.file("landscape.jpg").readBytes()

    @AfterEach
    fun tearDown() = frame.close()

    /** Alle Dateien im Fotoordner außer dem versteckten Upload-Ordner, relativ und sortiert. */
    private fun files() =
        frame.photos
            .walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(frame.photos).invariantSeparatorsPath }
            .filterNot { it.startsWith(".upload/") }
            .sorted()
            .toList()

    private fun result(res: io.github.tycho991.openframe.core.testing.Res) =
        ApiJson.decodeFromString(UploadResultDto.serializer(), res.body())

    @Test
    fun `Upload in Album`() {
        val res = frame.upload("familie", "a.jpg" to jpeg, "b.jpg" to jpeg)
        assertEquals(201, res.statusCode())
        val photos = result(res).photos
        assertEquals(listOf("familie/a.jpg", "familie/b.jpg"), photos.map { it.path })
        assertEquals(listOf("familie/a.jpg", "familie/b.jpg"), files())
        // Sofort im Index, ohne Scan.
        assertEquals(photos.map { it.id }.toSet(), frame.core.library.index.library.value.photos.keys)
        assertEquals(2, frame.notified.size, "dem MediaStore gemeldet")
        assertTrue(
            frame.photos
                .resolve(".upload")
                .listFiles()
                .orEmpty()
                .isEmpty(),
            "keine Reste im Upload-Ordner",
        )
    }

    @Test
    fun `Upload ohne Album`() {
        val res = frame.upload(null, "x.png" to Fixtures.file("image.png").readBytes())
        assertEquals(201, res.statusCode())
        assertEquals(null, result(res).photos.single().album)
        assertEquals(listOf("x.png"), files())
    }

    @Test
    fun `Pfad-Traversal`() {
        assertEquals(201, frame.upload(null, "../../evil.jpg" to jpeg).statusCode())
        assertEquals(201, frame.upload("../../x", "..\\..\\win.jpg" to jpeg).statusCode())
        assertEquals(201, frame.upload("a/b", "c.jpg" to jpeg).statusCode())
        assertEquals(listOf("ab/c.jpg", "evil.jpg", "x/win.jpg"), files())
        assertFalse(File(dir, "evil.jpg").exists())
        assertFalse(File(dir, "x").exists())
    }

    @Test
    fun `Gleicher Name`() {
        frame.upload("familie", "a.jpg" to jpeg)
        val res = frame.upload("familie", "a.jpg" to Fixtures.file("rotated6.jpg").readBytes())
        assertEquals("familie/a (1).jpg", result(res).photos.single().path)
        assertEquals(jpeg.size.toLong(), File(frame.photos, "familie/a.jpg").length(), "vorhandene Datei nicht überschrieben")
    }

    @Test
    fun `Kein unterstuetztes Format`() {
        val res = frame.upload("familie", "a.jpg" to jpeg, "notiz.txt" to "hallo".toByteArray())
        assertEquals(415, res.statusCode())
        assertEquals(emptyList<String>(), files(), "nichts gespeichert, auch nicht die gültige Datei")
        assertEquals(415, frame.upload(null, "foto.heic" to Fixtures.file("photo.heic").readBytes()).statusCode(), "HEIC auf altem Gerät")
        assertEquals(415, frame.upload(null, "kaputt.jpg" to Fixtures.file("broken.jpg").readBytes()).statusCode())
        assertEquals(emptyList<String>(), files())
    }

    @Test
    fun `Endung wird ergaenzt`() {
        val res = frame.upload(null, "bild" to jpeg)
        assertEquals("bild.jpg", result(res).photos.single().path)
    }

    @Test
    fun `Speicher voll`() {
        platform.free = PhotoLibrary.RESERVE_BYTES + jpeg.size / 2
        val res = frame.upload(null, "a.jpg" to jpeg)
        assertEquals(507, res.statusCode())
        assertEquals(emptyList<String>(), files())
        assertTrue(
            frame.photos
                .resolve(".upload")
                .listFiles()
                .orEmpty()
                .isEmpty(),
            "keine Teildatei",
        )
    }

    @Test
    fun `Speicher wird waehrend des Uploads knapp`() {
        val (type, body) = frame.multipart(null, "a.jpg" to jpeg + ByteArray(8 * 1024 * 1024))
        var sent = 0L
        platform.free = Long.MAX_VALUE / 2
        val res =
            frame.http.stream("POST", "/api/v1/photos", type, body.size.toLong(), frame.auth) {
                object : InputStream() {
                    private val inner = body.inputStream()

                    override fun read(): Int = inner.read()

                    override fun read(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int {
                        val n = inner.read(b, off, len)
                        if (n > 0) sent += n
                        if (sent > 2 * 1024 * 1024) platform.free = 1
                        return n
                    }
                }
            }
        assertEquals(507, res.statusCode())
        assertEquals(emptyList<String>(), files())
    }

    @Test
    fun `Upload von 300 MB mit begrenztem Heap`() {
        assertTrue(Runtime.getRuntime().maxMemory() < 300L * 1024 * 1024, "Test-Heap muss kleiner als der Upload sein")
        val filler = 300L * 1024 * 1024
        val boundary = "----big"
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"gross.jpg\"\r\n\r\n".toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val length = head.size + jpeg.size + filler + tail.size
        val res =
            frame.http.stream("POST", "/api/v1/photos", "multipart/form-data; boundary=$boundary", length, frame.auth) {
                // Ein gültiges JPEG mit 300 MB angehängten Nullbytes, erzeugt beim Senden.
                val zeros =
                    object : InputStream() {
                        var left = filler

                        override fun read(): Int = if (left-- > 0) 0 else -1

                        override fun read(
                            b: ByteArray,
                            off: Int,
                            len: Int,
                        ): Int {
                            if (left <= 0) return -1
                            val n = minOf(len.toLong(), left).toInt()
                            b.fill(0, off, off + n)
                            left -= n
                            return n
                        }
                    }
                SequenceInputStream(
                    java.util.Collections.enumeration(
                        listOf(ByteArrayInputStream(head), ByteArrayInputStream(jpeg), zeros, ByteArrayInputStream(tail)),
                    ),
                )
            }
        assertEquals(201, res.statusCode(), res.body())
        assertEquals(jpeg.size + filler, File(frame.photos, "gross.jpg").length())
    }

    @Test
    fun `Falscher Content-Type`() {
        assertEquals(415, frame.send("POST", "/api/v1/photos", """{"file":"x"}""").statusCode())
        val (type, _) = frame.multipart("familie")
        assertEquals(
            400,
            frame.http
                .requestBytes(
                    "POST",
                    "/api/v1/photos",
                    "--${type.substringAfter("=")}--\r\n".toByteArray(),
                    frame.auth,
                    type,
                ).statusCode(),
        )
    }
}
