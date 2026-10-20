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

    /** All files in the photo folder except the hidden upload folder, relative and sorted. */
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
    fun `Upload into album`() {
        val res = frame.upload("family", "a.jpg" to jpeg, "b.jpg" to jpeg)
        assertEquals(201, res.statusCode())
        val photos = result(res).photos
        assertEquals(listOf("family/a.jpg", "family/b.jpg"), photos.map { it.path })
        assertEquals(listOf("family/a.jpg", "family/b.jpg"), files())
        // In the index immediately, without a scan.
        assertEquals(photos.map { it.id }.toSet(), frame.core.library.index.library.value.photos.keys)
        assertEquals(2, frame.notified.size, "reported to the MediaStore")
        assertTrue(
            frame.photos
                .resolve(".upload")
                .listFiles()
                .orEmpty()
                .isEmpty(),
            "no leftovers in the upload folder",
        )
    }

    @Test
    fun `Upload without album`() {
        val res = frame.upload(null, "x.png" to Fixtures.file("image.png").readBytes())
        assertEquals(201, res.statusCode())
        assertEquals(null, result(res).photos.single().album)
        assertEquals(listOf("x.png"), files())
    }

    @Test
    fun `Path traversal`() {
        assertEquals(201, frame.upload(null, "../../evil.jpg" to jpeg).statusCode())
        assertEquals(201, frame.upload("../../x", "..\\..\\win.jpg" to jpeg).statusCode())
        assertEquals(201, frame.upload("a/b", "c.jpg" to jpeg).statusCode())
        assertEquals(listOf("ab/c.jpg", "evil.jpg", "x/win.jpg"), files())
        assertFalse(File(dir, "evil.jpg").exists())
        assertFalse(File(dir, "x").exists())
    }

    @Test
    fun `Same name`() {
        frame.upload("family", "a.jpg" to jpeg)
        val res = frame.upload("family", "a.jpg" to Fixtures.file("rotated6.jpg").readBytes())
        assertEquals("family/a (1).jpg", result(res).photos.single().path)
        assertEquals(jpeg.size.toLong(), File(frame.photos, "family/a.jpg").length(), "existing file not overwritten")
    }

    @Test
    fun `Unsupported format`() {
        val res = frame.upload("family", "a.jpg" to jpeg, "note.txt" to "hello".toByteArray())
        assertEquals(415, res.statusCode())
        assertEquals(emptyList<String>(), files(), "nothing stored, not even the valid file")
        assertEquals(415, frame.upload(null, "photo.heic" to Fixtures.file("photo.heic").readBytes()).statusCode(), "HEIC on an old device")
        assertEquals(415, frame.upload(null, "damaged.jpg" to Fixtures.file("broken.jpg").readBytes()).statusCode())
        assertEquals(emptyList<String>(), files())
    }

    @Test
    fun `Extension is added`() {
        val res = frame.upload(null, "image" to jpeg)
        assertEquals("image.jpg", result(res).photos.single().path)
    }

    @Test
    fun `Storage full`() {
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
            "no partial file",
        )
    }

    @Test
    fun `Storage runs low during the upload`() {
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
    fun `Upload of 300 MB with a limited heap`() {
        assertTrue(Runtime.getRuntime().maxMemory() < 300L * 1024 * 1024, "Test heap must be smaller than the upload")
        val filler = 300L * 1024 * 1024
        val boundary = "----big"
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.jpg\"\r\n\r\n".toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val length = head.size + jpeg.size + filler + tail.size
        val res =
            frame.http.stream("POST", "/api/v1/photos", "multipart/form-data; boundary=$boundary", length, frame.auth) {
                // A valid JPEG with 300 MB of appended null bytes, generated while sending.
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
        assertEquals(jpeg.size + filler, File(frame.photos, "big.jpg").length())
    }

    @Test
    fun `Wrong content type`() {
        assertEquals(415, frame.send("POST", "/api/v1/photos", """{"file":"x"}""").statusCode())
        val (type, _) = frame.multipart("family")
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
