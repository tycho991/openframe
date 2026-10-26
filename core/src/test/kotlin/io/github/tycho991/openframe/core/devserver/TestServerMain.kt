package io.github.tycho991.openframe.core.devserver

import fi.iki.elonen.NanoHTTPD
import io.github.tycho991.openframe.core.FrameCore
import io.github.tycho991.openframe.core.FrameDirs
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.nextcloud.FakeClients
import io.github.tycho991.openframe.core.nextcloud.FakeLoginFlow
import io.github.tycho991.openframe.core.platform.AssetSource
import io.github.tycho991.openframe.core.platform.Clock
import io.github.tycho991.openframe.core.platform.MediaStoreNotifier
import io.github.tycho991.openframe.core.sources.FakeRemoteFolder
import io.github.tycho991.openframe.core.sources.SourceException
import io.github.tycho991.openframe.core.testing.FakeDisplayPower
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

    /** The Nextcloud the frame talks to, with its shares: in memory, steered through the control port. */
    private var nextcloud = FakeClients()

    /** The display, with device administration active; steered through the control port. */
    private var displayPower = FakeDisplayPower()
    private lateinit var dataDir: File
    private var port = 0
    private lateinit var dist: File

    /** System time plus an offset that `/advance` moves, e.g. to let a pairing link expire. */
    private object TestServerClock : Clock {
        @Volatile var offset: Duration = Duration.ZERO

        override fun now(): Instant = Instant.now() + offset

        override fun monotonicMillis(): Long = java.lang.System.nanoTime() / 1_000_000 + offset.toMillis()
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
        nextcloud = FakeClients(login = FakeLoginFlow("http://127.0.0.1:$port"))
        displayPower = FakeDisplayPower()
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
                nextcloudClients = nextcloud,
                displayPower = displayPower,
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

                        // The user approves the login in the Nextcloud tab; `slow=3000` makes the answer take that many ms.
                        "/nextcloud/approve" -> {
                            (nextcloud.login as FakeLoginFlow).donePollDelayMs = q["slow"]?.toLong() ?: 0L
                            (nextcloud.login as FakeLoginFlow).approved = true
                            ok("approved")
                        }

                        // A share on the server: `/nextcloud/share?token=AbC123&password=pw`, `file=1` for the share of a single file,
                        // `drop=1` for a "file drop" that only accepts uploads, `name` for the name of the shared folder.
                        "/nextcloud/share" -> {
                            nextcloud.share(
                                q.getValue("token"),
                                q["password"].orEmpty(),
                                q["file"] != "1",
                                q["drop"] == "1",
                                q["name"] ?: "Geteilt",
                            )
                            ok("shared")
                        }

                        // A file in the folder of the account, or of a share with `share=<token>`:
                        // `/nextcloud/put?fixture=landscape.jpg&path=holiday/a.jpg&etag=e1`.
                        "/nextcloud/put" -> {
                            val path = q.getValue("path")
                            remoteOf(q).put(path, Fixtures.file(q.getValue("fixture")).readBytes(), q["etag"] ?: "etag-$path")
                            ok("put")
                        }

                        "/nextcloud/remove" -> {
                            remoteOf(q).remove(q.getValue("path"))
                            ok("removed")
                        }

                        // `/nextcloud/fail?on=1` lets listing the folder fail like a server that is down, `on=0` ends it.
                        "/nextcloud/fail" -> {
                            remoteOf(q).listFailure =
                                if (q["on"] ==
                                    "1"
                                ) {
                                    SourceException(
                                        SourceException.Kind.UNREACHABLE,
                                        "The server cloud.example.org did not answer in time",
                                    )
                                } else {
                                    null
                                }
                            ok("fail ${q["on"]}")
                        }

                        // Device administration activated (`on=1`) or deactivated (`on=0`) on the frame.
                        "/display/admin" -> {
                            displayPower.setAvailable(q["on"] == "1")
                            ok("admin ${q["on"]}")
                        }

                        // Someone presses the frame's power button.
                        "/display/power" -> {
                            displayPower.powerButton()
                            ok(if (displayPower.screenOn) "on" else "off")
                        }

                        // The display measured that its GPU is too slow to fade: the frame changes photos instantly.
                        "/transitions/slow" -> {
                            core.transitions.judge(frameMs = 14f, refreshMs = 17.65f)
                            ok("slow")
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

        /** The folder of the share named by `share`, or of the account without it. */
        private fun remoteOf(q: Map<String, String>): FakeRemoteFolder =
            q["share"]?.let { nextcloud.shares.getValue(it).remote } ?: nextcloud.remote
    }
}
