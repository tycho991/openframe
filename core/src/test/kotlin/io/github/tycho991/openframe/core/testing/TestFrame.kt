package io.github.tycho991.openframe.core.testing

import io.github.tycho991.openframe.core.FrameCore
import io.github.tycho991.openframe.core.FrameDirs
import io.github.tycho991.openframe.core.platform.AssetSource
import io.github.tycho991.openframe.core.platform.MediaStoreNotifier
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** Ein vollständiger [FrameCore] auf einem freien Port, mit gekoppeltem Client und Contract-Prüfung. */
class TestFrame(
    dir: File,
    val engine: JvmMediaEngine = JvmMediaEngine(),
    val platform: FakePlatform = FakePlatform(),
    scanInterval: Duration = Duration.ofMillis(200),
) : AutoCloseable {
    val dirs = FrameDirs(File(dir, "state"), File(dir, "cache"), File(dir, "photos"))
    val notified = CopyOnWriteArrayList<File>()
    val assets = AssetSource { path -> if (path == "index.html") "<!doctype html>".byteInputStream() else null }
    val core =
        FrameCore(dirs, platform, engine, assets, MediaStoreNotifier { notified += it }, port = 0, scanInterval = scanInterval).also {
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

    /** Baut eine multipart-Anfrage aus Feldern und Dateien. */
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
