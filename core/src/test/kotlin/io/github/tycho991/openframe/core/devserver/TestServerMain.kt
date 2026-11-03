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
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.time.Duration
import java.time.Instant

/**
 * `:core` as a test server for the web UI's Playwright tests, with a fake MediaEngine.
 * Serves the built web UI and has a control port for the tests; both listen on loopback only.
 *
 * Usage: `./gradlew :core:runTestServer --args="<port> <control-port> <webadmin/dist>"`. The control port takes only
 * GET requests with the header `x-openframe-control: 1`, e.g. `curl -H 'x-openframe-control: 1' 127.0.0.1:8091/code`.
 * The data lives in `core/build/testserver/<port>`, emptied at every start and deleted when the server ends; a second
 * server on the same port refuses to start instead of emptying it.
 */
object TestServerMain {
    /** The header every request to the control port must carry, with the value `1`. */
    const val CONTROL_HEADER = "x-openframe-control"

    /** The folder for the data of the test servers, one subfolder per port; set by the task `runTestServer`. */
    private const val DATA_DIR_PROPERTY = "openframe.testserver.dir"

    private lateinit var core: FrameCore

    /** The Nextcloud the frame talks to, with its shares: in memory, steered through the control port. */
    private var nextcloud = FakeClients()

    /** The display, with device administration active; steered through the control port. */
    private var displayPower = FakeDisplayPower()
    private lateinit var dataDir: File

    /** Held as long as the server runs, see [main]. */
    private var dataLock: FileLock? = null
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
        // One folder per port, so that the servers of parallel worktrees never share their data. Emptied first: a
        // server that was killed could not delete it.
        val home = File(System.getProperty(DATA_DIR_PROPERTY) ?: "core/build/testserver").absoluteFile.apply { mkdirs() }
        dataDir = File(home, port.toString())
        // Claimed before it is emptied: a second server on the same port must not wipe the data of the running one.
        // The system releases the lock when the process ends, also when it is killed.
        dataLock = RandomAccessFile(File(home, "$port.lock"), "rw").channel.tryLock()
            ?: error("Another test server already uses $dataDir")
        dataDir.deleteRecursively()
        dataDir.mkdirs()
        synchronized(TestServerMain) { startCore() }
        Control(controlPort).start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        // Only after a successful start: a server that could not start leaves the folder to the next start.
        Runtime.getRuntime().addShutdownHook(Thread(::shutdown, "testserver-shutdown"))
        println(
            "openframe test server on http://127.0.0.1:$port/ " +
                "(control on 127.0.0.1:$controlPort with the header $CONTROL_HEADER: 1, data in $dataDir)",
        )
        Thread.currentThread().join()
    }

    /**
     * On SIGTERM or Ctrl+C: stops the frame and deletes its data. Under the lock, so that it neither runs into a
     * reset nor into a start that has not finished.
     */
    private fun shutdown() {
        synchronized(TestServerMain) {
            if (::core.isInitialized) runCatching { runBlocking { core.stop() } }
            dataDir.deleteRecursively()
        }
    }

    /**
     * The file at [relative] inside [root], or `null` if it would lie outside of it: through `..`, as an absolute
     * path or through a symbolic link. [root] itself does not count as inside.
     */
    internal fun resolveInside(
        root: File,
        relative: String,
    ): File? {
        if (File(relative).isAbsolute) return null
        val base = root.canonicalFile
        val target = File(base, relative).canonicalFile
        return target.takeIf { it.path.startsWith(base.path + File.separator) }
    }

    /**
     * Why the control port refuses a request, or `null` if it takes it: only GET without a body, only with
     * [CONTROL_HEADER] and only for the host `127.0.0.1` or `localhost` on [controlPort]. The header keeps other web
     * pages out, because a browser asks before it sends such a header to another site and the control port refuses that
     * question. The host keeps out a page whose own name was pointed at 127.0.0.1 (DNS rebinding) and that could send
     * the header to itself. A refusal also closes the connection (see [Control]): NanoHTTPD would otherwise read the
     * unread body of a refused request, e.g. of a form sent by another page, as the next request.
     */
    internal fun controlRefusal(
        method: NanoHTTPD.Method?,
        headers: Map<String, String>,
        controlPort: Int,
    ): Pair<NanoHTTPD.Response.Status, String>? =
        when {
            method != NanoHTTPD.Method.GET -> {
                NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED to "GET only"
            }

            headers["content-length"].let { it != null && it != "0" } || "transfer-encoding" in headers -> {
                NanoHTTPD.Response.Status.BAD_REQUEST to "no body"
            }

            headers[CONTROL_HEADER] != "1" -> {
                NanoHTTPD.Response.Status.FORBIDDEN to "the header $CONTROL_HEADER: 1 is missing"
            }

            headers["host"] !in setOf("127.0.0.1:$controlPort", "localhost:$controlPort") -> {
                NanoHTTPD.Response.Status.FORBIDDEN to "unknown host ${headers["host"]}"
            }

            else -> {
                null
            }
        }

    private fun startCore() {
        val assets =
            AssetSource { path ->
                // With the separator, so that a neighbouring folder such as `dist-old` is not inside.
                File(dist, path).takeIf { it.isFile && it.canonicalPath.startsWith(dist.canonicalPath + File.separator) }?.inputStream()
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
                bindLoopback = true,
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

    internal class Control(
        private val controlPort: Int,
    ) : NanoHTTPD("127.0.0.1", controlPort) {
        override fun serve(session: IHTTPSession): Response {
            controlRefusal(session.method, session.headers, controlPort)?.let { (status, reason) ->
                return newFixedLengthResponse(status, "text/plain", reason).apply { closeConnection(true) }
            }
            return synchronized(TestServerMain) {
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

                        // Simulates a file copied over USB, into the photo folder only.
                        "/usb" -> {
                            val target = resolveInside(core.localSource.root, q.getValue("to"))
                            if (target == null) {
                                newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "outside the photo folder")
                            } else {
                                Fixtures.copy(q.getValue("fixture"), target)
                                // Scan right away unless scan=0, so tests do not wait for the interval.
                                if (q["scan"] != "0") runBlocking { core.localSource.scan() }
                                ok("copied")
                            }
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
                                    // Copied under a hidden name and renamed: the scan running meanwhile never reads a
                                    // half-copied file, which it would take as corrupt and drop from the index.
                                    val part = File(file.parentFile, ".${file.name}.part")
                                    fixture.copyTo(part, overwrite = true)
                                    check(part.renameTo(file)) { "Could not rename $part" }
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

                        // A local photo as if its rendering had crashed the decoder, e.g. `/quarantine?path=a.jpg`; answers its ID.
                        "/quarantine" -> {
                            val id = PhotoEntry.idFor("local", q.getValue("path"))
                            core.library.index.edit { edit ->
                                val entry = edit.get(id) ?: error("No photo ${q.getValue("path")}")
                                edit.put(entry.copy(quarantinedVersion = entry.version))
                            }
                            ok(id)
                        }

                        // The caption the frame shows over the photo on screen: its line and its place, each on a line of
                        // its own (empty without one).
                        "/caption" -> {
                            val text = core.caption.value?.text
                            ok(text?.line.orEmpty() + "\n" + text?.place.orEmpty())
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
        }

        private fun ok(text: String) = newFixedLengthResponse(Response.Status.OK, "text/plain", text)

        /** The folder of the share named by `share`, or of the account without it. */
        private fun remoteOf(q: Map<String, String>): FakeRemoteFolder =
            q["share"]?.let { nextcloud.shares.getValue(it).remote } ?: nextcloud.remote
    }
}
