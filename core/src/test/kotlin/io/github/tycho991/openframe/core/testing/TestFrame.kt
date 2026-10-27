package io.github.tycho991.openframe.core.testing

import io.github.tycho991.openframe.core.FrameCore
import io.github.tycho991.openframe.core.FrameDirs
import io.github.tycho991.openframe.core.nextcloud.NextcloudClients
import io.github.tycho991.openframe.core.platform.AssetSource
import io.github.tycho991.openframe.core.platform.Clock
import io.github.tycho991.openframe.core.platform.DisplayPower
import io.github.tycho991.openframe.core.platform.MediaStoreNotifier
import io.github.tycho991.openframe.core.platform.NetworkLink
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** A complete [FrameCore] on a free port, with a paired client and contract check. */
class TestFrame(
    dir: File,
    val engine: JvmMediaEngine = JvmMediaEngine(),
    val platform: FakePlatform = FakePlatform(),
    scanInterval: Duration = Duration.ofMillis(200),
    nextcloudClients: NextcloudClients? = null,
    clock: Clock = Clock.System,
    displayPower: DisplayPower = DisplayPower.None,
    networkLink: NetworkLink = NetworkLink.None,
) : AutoCloseable {
    val dirs = FrameDirs(File(dir, "state"), File(dir, "cache"), File(dir, "photos"))
    val notified = CopyOnWriteArrayList<File>()
    val assets = AssetSource { path -> if (path == "index.html") "<!doctype html>".byteInputStream() else null }
    val core =
        FrameCore(
            dirs,
            platform,
            engine,
            assets,
            MediaStoreNotifier {
                notified += it
            },
            clock = clock,
            port = 0,
            scanInterval = scanInterval,
            nextcloudClients = nextcloudClients,
            displayPower = displayPower,
            networkLink = networkLink,
        ).also {
            runBlocking { it.start() }
        }
    val http = TestHttp("http://127.0.0.1:${core.server.boundPort}")
    val token: String = pair("Test")
    val auth: Map<String, String> = mapOf("Authorization" to "Bearer $token")

    val photos: File get() = dirs.photos

    fun pair(name: String): String {
        core.pairing.requestDisplay()
        return core.pairing.pair(core.pairing.display.value.code!!, name).first
    }

    fun get(path: String) = http.get(path, auth)

    fun send(
        method: String,
        path: String,
        body: String? = null,
    ) = http.request(method, path, body, auth)

    fun scan() = runBlocking { core.localSource.scan() }

    /** Builds a multipart request from fields and files. */
    fun multipart(
        album: String?,
        vararg files: Pair<String, ByteArray>,
    ): Pair<String, ByteArray> {
        val boundary = "----test${System.nanoTime()}"
        val out = ByteArrayOutputStream()
        if (album != null) {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"album\"\r\n\r\n$album\r\n".toByteArray())
        }
        for ((name, data) in files) {
            out.write(
                "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: application/octet-stream\r\n\r\n"
                    .toByteArray(),
            )
            out.write(data)
            out.write("\r\n".toByteArray())
        }
        out.write("--$boundary--\r\n".toByteArray())
        return "multipart/form-data; boundary=$boundary" to out.toByteArray()
    }

    fun upload(
        album: String?,
        vararg files: Pair<String, ByteArray>,
    ): Res {
        val (type, body) = multipart(album, *files)
        return http.requestBytes("POST", "/api/v1/photos", body, auth, type)
    }

    override fun close() {
        runBlocking { core.stop() }
    }
}
