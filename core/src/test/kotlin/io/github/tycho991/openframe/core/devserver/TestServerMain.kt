package io.github.tycho991.openframe.core.devserver

import fi.iki.elonen.NanoHTTPD
import io.github.tycho991.openframe.core.FrameCore
import io.github.tycho991.openframe.core.FrameDirs
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.platform.AssetSource
import io.github.tycho991.openframe.core.platform.MediaStoreNotifier
import io.github.tycho991.openframe.core.testing.FakePlatform
import io.github.tycho991.openframe.core.testing.Fixtures
import io.github.tycho991.openframe.core.testing.JvmMediaEngine
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.time.Duration

/**
 * `:core` als Testserver für die Playwright-Tests der Web-UI, mit Fake-MediaEngine.
 * Liefert die gebaute Web-UI aus und hat einen Steuer-Port für die Tests.
 *
 * Aufruf: `./gradlew :core:runTestServer --args="<port> <steuer-port> <webadmin/dist>"`
 */
object TestServerMain {
    private lateinit var core: FrameCore
    private lateinit var dataDir: File
    private var port = 0
    private lateinit var dist: File

    @JvmStatic
    fun main(args: Array<String>) {
        port = args.getOrNull(0)?.toInt() ?: 8090
        val controlPort = args.getOrNull(1)?.toInt() ?: 8091
        dist = File(args.getOrNull(2) ?: "webadmin/dist").absoluteFile
        dataDir = Files.createTempDirectory("openframe-testserver").toFile()
        startCore()
        Control(controlPort).start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        println("openframe-Testserver auf http://127.0.0.1:$port/ (Steuerung auf $controlPort, Daten in $dataDir)")
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
                port = port,
                scanInterval = Duration.ofSeconds(2),
            )
        runBlocking { core.start() }
    }

    private fun reset() {
        runBlocking { core.stop() }
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

                        // Simuliert eine per USB kopierte Datei.
                        "/usb" -> {
                            Fixtures.copy(q.getValue("fixture"), File(core.localSource.root, q.getValue("to")))
                            // Ohne scan=0 gleich scannen, damit Tests nicht auf das Intervall warten.
                            if (q["scan"] != "0") runBlocking { core.localSource.scan() }
                            ok("copied")
                        }

                        // Viele Fotos auf einmal, ohne dass der Scan sie alle öffnen muss.
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
                            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "unbekannt")
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
