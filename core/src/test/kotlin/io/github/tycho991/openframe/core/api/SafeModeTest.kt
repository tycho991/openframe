package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.health.Health
import io.github.tycho991.openframe.core.health.RunJournal
import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.nextcloud.FakeNextcloud
import io.github.tycho991.openframe.core.nextcloud.NextcloudHttp
import io.github.tycho991.openframe.core.nextcloud.OkHttpNextcloudClients
import io.github.tycho991.openframe.core.slideshow.SlideState
import io.github.tycho991.openframe.core.testing.FakeProcessControl
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.TestClock
import io.github.tycho991.openframe.core.testing.TestFrame
import io.github.tycho991.openframe.core.testing.waitFor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `kiosk-host`: Safe mode after repeated crashes, Leaving safe mode; through the API with the contract check. */
class SafeModeTest {
    @TempDir
    lateinit var dir: File

    private val clock = TestClock()
    private val control = FakeProcessControl()

    private fun safeFrame(
        sinceMs: Long = clock.millis(),
        nextcloud: FakeNextcloud? = null,
    ) = TestFrame(
        dir,
        clock = clock,
        health = Health.safe(sinceMs),
        processControl = control,
        nextcloudClients = nextcloud?.let { OkHttpNextcloudClients { NextcloudHttp(NextcloudHttp.defaultClient(readTimeoutSeconds = 5)) } },
    )

    private fun errorCode(res: io.github.tycho991.openframe.core.testing.Res) =
        Json
            .parseToJsonElement(res.body())
            .jsonObject["error"]!!
            .jsonObject["code"]!!
            .jsonPrimitive.content

    private fun safeMode(frame: TestFrame) =
        Json
            .parseToJsonElement(frame.get("/api/v1/state").body())
            .jsonObject["safeMode"]!!
            .jsonPrimitive.boolean

    @Test
    fun `In safe mode images, uploads and syncs rest, the rest works, and nothing is decoded`() {
        Fixtures.copy("landscape.jpg", File(File(dir, "photos"), "a.jpg"))
        val nextcloud = FakeNextcloud().apply { start() }
        try {
            nextcloud.put("Shared/b.jpg", Fixtures.file("landscape.jpg").readBytes(), "e1")
            nextcloud.shares["AbC123"] = FakeNextcloud.Share("Shared")
            safeFrame(nextcloud = nextcloud).use { frame ->
                assertTrue(safeMode(frame))
                assertTrue(frame.core.health.safe)

                val image = frame.get("/api/v1/photos/local-${"a".repeat(40)}/image?size=thumb")
                assertEquals(503, image.statusCode())
                assertEquals("safe_mode", errorCode(image))
                val upload = frame.upload(null, "b.jpg" to Fixtures.file("landscape.jpg").readBytes())
                assertEquals(503, upload.statusCode())
                assertEquals("safe_mode", errorCode(upload))

                // A share is stored, but neither synced now nor after a network change.
                val added =
                    frame.send("POST", "/api/v1/sources", """{"kind":"nextcloud-share","link":"${nextcloud.baseUrl}/s/AbC123"}""")
                assertEquals(201, added.statusCode(), added.body())
                val id = ApiJson.decodeFromString(SourceDto.serializer(), added.body()).id
                val sync = frame.send("POST", "/api/v1/sources/$id/sync")
                assertEquals(503, sync.statusCode())
                assertEquals("safe_mode", errorCode(sync))

                // Settings, pairing and the display schedule work; the slideshow and the scan do not run.
                assertEquals(200, frame.send("PATCH", "/api/v1/settings", """{"intervalSeconds":60}""").statusCode())
                assertEquals(200, frame.get("/api/v1/clients").statusCode())
                assertEquals(200, frame.get("/api/v1/display").statusCode())

                // Last, because it binds the server anew on another port of the test.
                frame.core.onNetworkChanged()
                Thread.sleep(500)
                val status = frame.core.sources.status(id)
                assertFalse(status.syncing)
                assertNull(status.last?.lastSyncAtMs, "no sync")
                assertTrue(nextcloud.requests.none { it.startsWith("GET") }, "nothing downloaded: ${nextcloud.requests}")
                assertEquals(SlideState.Loading, frame.core.slideshow.state.value)
                assertEquals(
                    0,
                    frame.engine.probes.get() + frame.engine.renders.get() + frame.engine.metadataReads.get(),
                    "the engine got no call",
                )
            }
        } finally {
            nextcloud.stop()
        }
    }

    @Test
    fun `Start normally from a paired device`() {
        safeFrame().use { frame ->
            val res = frame.send("POST", "/api/v1/safe-mode/exit", "{}")
            assertEquals(202, res.statusCode())
            waitFor(message = "restarted") { control.restarts.isNotEmpty() }
            assertEquals(listOf("api"), control.restarts)
        }
        TestFrame(File(dir, "normal")).use { frame ->
            val res = frame.send("POST", "/api/v1/safe-mode/exit", "{}")
            assertEquals(409, res.statusCode())
            assertEquals("not_in_safe_mode", errorCode(res))
            assertFalse(safeMode(frame))
        }
    }

    @Test
    fun `The probe restarts the app in normal mode once its time has come`() {
        safeFrame(sinceMs = clock.millis() - RunJournal.HOUR_MS).use {
            waitFor(message = "probe") { control.restarts.isNotEmpty() }
            assertEquals(listOf("probe"), control.restarts)
        }
    }

    @Test
    fun `A run in normal mode becomes stable after the time, and its crash is no early end`() {
        val file = File(dir, "runs.json")

        fun start() = RunJournal(file).let { it to it.onProcessStart(clock.millis(), 60_000, "boot", "1") }
        val (journal, state) = start()
        TestFrame(File(dir, "frame"), health = Health(journal, state) { 60_000 }, stableAfterMs = 100).use { frame ->
            Thread.sleep(400)
            assertTrue(frame.core.ready.value)
        }
        journal.onJavaCrash("java.lang.IllegalStateException")
        assertEquals(0, start().second.recentEarlyEnds)
    }
}
