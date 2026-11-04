package io.github.tycho991.openframe.core.store

import io.github.tycho991.openframe.core.FrameCore
import io.github.tycho991.openframe.core.FrameDirs
import io.github.tycho991.openframe.core.library.Crop
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.platform.AssetSource
import io.github.tycho991.openframe.core.platform.MediaStoreNotifier
import io.github.tycho991.openframe.core.testing.FakePlatform
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import io.github.tycho991.openframe.core.testing.TestFrame
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `frame-settings`: Durable, atomic storage, in the real core. */
class StoreRecoveryTest {
    @TempDir
    lateinit var dir: File

    private val state get() = File(dir, "state")

    @Test
    fun `A damaged index comes back from the copy of the last start with comment and section`() {
        val id = PhotoEntry.idFor("local", "a.jpg")
        TestFrame(dir).use { frame ->
            Fixtures.copy("landscape.jpg", File(frame.photos, "a.jpg"))
            frame.scan()
            frame.send("PATCH", "/api/v1/photos/$id", """{"comment":"Grandma's garden","crop":{"x":0.5,"y":0.2,"zoom":1.5}}""")
        }
        // This start reads the index and keeps the copy.
        TestFrame(dir).close()
        assertTrue(File(state, "index.json.bak").isFile)
        File(state, "index.json").writeText("{ damaged")

        TestFrame(dir).use { frame ->
            val entry = frame.core.library.index.library.value.photos[id]!!
            assertEquals("Grandma's garden", entry.comment)
            assertEquals(Crop(0.5, 0.2, 1.5), entry.crop)
        }
    }

    @Test
    fun `A damaged list of paired devices never falls back to a copy`() {
        val old = TestFrame(dir).use { it.token }
        TestFrame(dir).close()
        assertFalse(File(state, "clients.json.bak").exists(), "a copy would bring back revoked tokens")
        assertFalse(File(state, "secret.json.bak").exists())
        assertFalse(File(state, "sources.json.bak").exists())
        File(state, "clients.json").writeText("{ damaged")

        TestFrame(dir).use { frame ->
            assertEquals(401, frame.http.get("/api/v1/clients", mapOf("Authorization" to "Bearer $old")).statusCode())
            assertEquals(200, frame.get("/api/v1/clients").statusCode())
        }
    }

    @Test
    fun `The core starts with an unwritable state folder and neither identity nor key`() {
        assumeFalse(System.getProperty("user.name") == "root", "root writes everywhere")
        state.mkdirs()
        val dirs = FrameDirs(state, File(dir, "cache"), File(dir, "photos"))
        val core =
            FrameCore(
                dirs,
                FakePlatform(),
                JvmMediaEngine(),
                AssetSource { null },
                MediaStoreNotifier {},
                port = 0,
                bindLoopback = true,
            )
        state.setWritable(false)
        try {
            runBlocking { core.start() }
            assertEquals(32, core.identity.id.length)
            assertTrue(core.ready.value)
        } finally {
            state.setWritable(true)
            runBlocking { core.stop() }
        }
    }
}
