package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.testing.FakeAppInstaller
import io.github.tycho991.openframe.core.testing.FakePlatform
import io.github.tycho991.openframe.core.testing.FakeReleaseSource
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.TestClock
import io.github.tycho991.openframe.core.testing.TestFrame
import io.github.tycho991.openframe.core.testing.waitFor
import io.github.tycho991.openframe.core.update.UpdatePhase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.Socket
import java.time.Duration
import java.time.Instant

/**
 * Spec `photo-library`: Trash under storage pressure, Where storage pressure frees the trash (design decision 9 of
 * `paired-device-guards`), with storage whose free bytes grow with what the trash gives back.
 */
class TrashPressureTest {
    @TempDir
    lateinit var dir: File

    private val clock = TestClock(Instant.parse("2026-11-20T10:00:00Z"))
    private val platform = FakePlatform(appVersion = "0.3.0", appVersionCode = 300)
    private val releases = FakeReleaseSource()
    private var opened: TestFrame? = null
    private val frame: TestFrame
        get() =
            opened ?: TestFrame(dir, platform = platform, clock = clock, installer = FakeAppInstaller(), releases = releases)
                .also { opened = it }
    private val jpeg = Fixtures.file("landscape.jpg").readBytes()

    @AfterEach
    fun tearDown() {
        opened?.close()
    }

    private fun trashBytes(): Long =
        File(frame.photos, ".trash")
            .walkTopDown()
            .filter { it.isFile && it.name != ".nomedia" }
            .sumOf { it.length() }

    private fun trashIds() = ApiJson.decodeFromString(TrashListDto.serializer(), frame.get("/api/v1/trash").body()).items.map { it.id }

    /** Three photos deleted a minute apart, two days ago; returns their trash IDs oldest first and the size of one. */
    private fun oldTrash(): Pair<List<String>, Long> {
        for (name in listOf("1.jpg", "2.jpg", "3.jpg")) {
            Fixtures.copy("landscape.jpg", File(frame.photos, name))
            frame.scan()
            assertEquals(204, frame.send("DELETE", "/api/v1/photos/${PhotoEntry.idFor("local", name)}").statusCode())
            clock.advance(Duration.ofMinutes(1))
        }
        clock.advance(Duration.ofDays(2))
        return trashIds().reversed() to Fixtures.file("landscape.jpg").length()
    }

    /** Free storage [short] bytes below [needed], growing with every byte the trash gives back. */
    private fun shortBy(
        needed: Long,
        short: Long,
    ) {
        val start = trashBytes()
        platform.freeOf = { needed - short + (start - trashBytes()) }
    }

    /** › Trash under storage pressure › Upload makes room. */
    @Test
    fun `An upload short of room deletes the oldest old entries it needs and succeeds`() {
        val (ids, size) = oldTrash()
        shortBy(frame.core.reserveBytes, size + size / 2)
        val res = frame.upload(null, "new.jpg" to jpeg)
        assertEquals(201, res.statusCode(), res.body())
        assertEquals(listOf(ids[2]), trashIds(), "the two oldest went, the newest stays")
        assertTrue(
            frame.core.log
                .recent(500)
                .any { it.message.startsWith("Trash purged: 2 entries") },
        )
    }

    /** › Trash under storage pressure › Not enough to gain. */
    @Test
    fun `An upload that all old entries together could not fit gets 507 and the trash stays`() {
        val (ids, size) = oldTrash()
        shortBy(frame.core.reserveBytes, 4 * size)
        val res = frame.upload(null, "new.jpg" to jpeg)
        assertEquals(507, res.statusCode())
        assertEquals(ids.toSet(), trashIds().toSet())
    }

    /** › Trash under storage pressure › Only recent entries. */
    @Test
    fun `With only recent entries an upload gets 507 and the trash stays`() {
        val (ids, size) = oldTrash()
        clock.instant = clock.instant - Duration.ofDays(2)
        shortBy(frame.core.reserveBytes, size / 2)
        val res = frame.upload(null, "new.jpg" to jpeg)
        assertEquals(507, res.statusCode())
        assertEquals(ids.toSet(), trashIds().toSet())
    }

    /** › Where storage pressure frees the trash › Announced size of an upload. */
    @Test
    fun `An announced length deletes nothing before the body is written`() {
        val (ids, size) = oldTrash()
        shortBy(frame.core.reserveBytes + size, 0)
        // Headers only: an announced body beyond the free storage above the reserve, which never comes.
        Socket("127.0.0.1", frame.core.server.boundPort!!).use { socket ->
            socket.soTimeout = 2_000
            val headers =
                "POST /api/v1/photos HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer ${frame.token}\r\n" +
                    "Content-Type: multipart/form-data; boundary=x\r\nContent-Length: ${2 * size}\r\n\r\n"
            socket.getOutputStream().write(headers.toByteArray())
            socket.getOutputStream().flush()
            Thread.sleep(500)
            assertEquals(ids.toSet(), trashIds().toSet(), "nothing deleted while the body has not come")
        }
        // An announced length beyond what the trash could free is refused at once, also deleting nothing.
        shortBy(frame.core.reserveBytes, 0)
        val (type, body) = frame.multipart(null, "big.jpg" to ByteArray((4 * size).toInt()) { jpeg[it % jpeg.size] })
        val res = frame.http.requestBytes("POST", "/api/v1/photos", body, frame.auth, type)
        assertEquals(507, res.statusCode())
        assertEquals(ids.toSet(), trashIds().toSet())
    }

    /** › Where storage pressure frees the trash › Update on a full frame. */
    @Test
    fun `An update short of storage is downloaded after old entries are deleted`() {
        val (ids, size) = oldTrash()
        val apk = Fixtures.apk("0.4.0")
        releases.publish("0.4.0", apk)
        shortBy(2L * apk.size + 100 * 1024 * 1024, size / 2)
        assertEquals(202, frame.send("POST", "/api/v1/update/check").statusCode())
        waitFor(message = "the update ready") { frame.core.update.state.value.phase == UpdatePhase.READY }
        assertEquals(listOf(ids[1], ids[2]).toSet(), trashIds().toSet(), "only the oldest went")
    }
}
