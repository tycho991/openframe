package io.github.tycho991.openframe.core.devserver

import fi.iki.elonen.NanoHTTPD
import io.github.tycho991.openframe.core.FrameCore
import io.github.tycho991.openframe.core.FrameDirs
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.platform.AssetSource
import io.github.tycho991.openframe.core.platform.Clock
import io.github.tycho991.openframe.core.platform.MediaStoreNotifier
import io.github.tycho991.openframe.core.testing.FakePlatform
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

/**
 * `:core` as a test server for the web UI's Playwright tests, with a fake MediaEngine.
 * Serves the built web UI and has a control port for the tests.
 *
 * Usage: `./gradlew :core:runTestServer --args="<port> <control-port> <webadmin/dist>"`
 */
object TestServerMain {
    private lateinit var core: FrameCore
    private lateinit var dataDir: File
    private var port = 0
    private lateinit var dist: File

    /** System time plus an offset that `/advance` moves, e.g. to let a pairing link expire. */
    private object TestServerClock : Clock {
        @Volatile var offset: Duration = Duration.ZERO

        override fun now(): Instant = Instant.now() + offset
    }

    @JvmStatic
    fun main(args: Array<String>) {
        port = args.getOrNull(0)?.toInt() ?: 8090
        val controlPort = args.getOrNull(1)?.toInt() ?: 8091
        dist = File(args.getOrNull(2) ?: "webadmin/dist").absoluteFile
        dataDir = Files.createTempDirectory("openframe-testserver").toFile()
        startCore()
        Control(controlPort).start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        println("openframe test server on http://127.0.0.1:$port/ (control on $controlPort, data in $dataDir)")
        Thread.currentThread().join()
    }

    private fun startCore() {
        val assets =
            AssetSource { path ->
                File(dist, path).takeIf { it.isFile && it.canonicalPath.startsWith(dist.canonicalPath) }?.inputStream()
            }
        val dirs = FrameDirs(File(dataDir, "state"), File(dataDir, "cache"), File(dataDir, "photos"))
        core =
            FrameCore(
                dirs,
                FakePlatform(),
                JvmMediaEngine(supportsHeic = false),
                assets,
                MediaStoreNotifier.None,
                clock = TestServerClock,
                port = port,
                scanInterval = Duration.ofSeconds(2),
            )
        runBlocking { core.start() }
    }

    private fun reset() {
        runBlocking { core.stop() }
        TestServerClock.offset = Duration.ZERO
        dataDir.deleteRecursively()
        dataDir.mkdirs()
        startCore()
    }

    private class Control(
        port: Int,
    ) : NanoHTTPD("127.0.0.1", port) {
        override fun serve(session: IHTTPSession): Response =
            synchronized(TestServerMain) {
                val q = session.parameters.mapValues { it.value.first() }
                try {
                    when (session.uri) {
                        "/reset" -> {
                            reset().let { ok("reset") }
                        }

                        "/code" -> {
                            core.pairing.requestDisplay()
                            ok(core.pairing.display.value.code ?: "")
                        }

                        // The secret of the QR code the frame is currently showing.
                        "/qr" -> {
                            core.pairing.requestDisplay()
                            ok(
                                core.pairing.display.value.url
                                    ?.substringAfter("#pair=") ?: "",
                            )
                        }

                        // Moves the frame's clock forward, e.g. `/advance?seconds=301`.
                        "/advance" -> {
                            TestServerClock.offset += Duration.ofSeconds(q.getValue("seconds").toLong())
                            core.pairing.tick()
                            ok("advanced")
                        }

                        // Simulates a file copied over USB.
                        "/usb" -> {
                            Fixtures.copy(q.getValue("fixture"), File(core.localSource.root, q.getValue("to")))
                            // Scan right away unless scan=0, so tests do not wait for the interval.
                            if (q["scan"] != "0") runBlocking { core.localSource.scan() }
                            ok("copied")
                        }

                        // Many photos at once, without the scan having to open them all.
                        "/many" -> {
                            val n = q.getValue("n").toInt()
                            val fixture = Fixtures.file("landscape.jpg")
                            core.library.index.edit { edit ->
                                for (i in 0 until n) {
                                    val path = "viele/foto%05d.jpg".format(i)
                                    val file = File(core.localSource.root, path)
                                    file.parentFile.mkdirs()
                                    fixture.copyTo(file, overwrite = true)
                                    edit.put(
                                        PhotoEntry(
                                            PhotoEntry.idFor("local", path),
                                            "local",
                                            path,
                                            400,
                                            300,
                                            1_600_000_000_000L + i * 60_000L,
                                            file.length(),
                                            file.lastModified(),
                                            1,
                                        ),
                                    )
                                }
                            }
                            ok("added $n")
                        }

                        else -> {
                            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "unknown")
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.toString())
                }
            }

        private fun ok(text: String) = newFixedLengthResponse(Response.Status.OK, "text/plain", text)
    }
}
