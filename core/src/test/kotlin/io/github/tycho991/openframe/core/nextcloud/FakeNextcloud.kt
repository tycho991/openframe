@file:Suppress("ktlint:standard:max-line-length")

package io.github.tycho991.openframe.core.nextcloud

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone

/**
 * A Nextcloud server for the tests: Login Flow v2, the user lookup, `PROPFIND` and `GET` on a folder tree, and faults
 * that can be switched on. Plain `http` on a free port of the loopback address.
 */
class FakeNextcloud(
    val userId: String = "anna",
    val loginName: String = "Anna",
    val appPassword: String = "app-pw-4711",
) : NanoHTTPD("127.0.0.1", 0) {
    class Remote(
        var content: ByteArray,
        var etag: String,
        var modifiedMs: Long = 1_760_000_000_000L,
    )

    /** Files by their path below the user's root, e.g. `Photos/holiday/a.jpg`. */
    val files = LinkedHashMap<String, Remote>()

    /** Folders that exist without a file in them. */
    val folders = LinkedHashSet<String>()

    /** `METHOD path` of every request that arrived, in order. */
    val requests = ArrayList<String>()

    /** Status codes to answer with for a path (as the request names it). */
    val faults = HashMap<String, Int>()

    /** Paths that redirect (301) to another URL. */
    val redirects = HashMap<String, String>()

    /** Waits this long before sending the body of a download. */
    @Volatile var downloadDelayMs = 0L

    @Volatile var approved = false

    @Volatile var revoked = false

    val baseUrl: String get() = "http://127.0.0.1:$listeningPort"

    fun put(
        path: String,
        content: ByteArray = path.toByteArray(),
        etag: String = "etag-" + path.hashCode(),
    ) {
        files[path] = Remote(content, etag)
    }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        drainBody(session)
        synchronized(requests) { requests += "${session.method} $path" }
        redirects[path]?.let {
            return newFixedLengthResponse(
                Response.Status.lookup(301),
                "text/plain",
                "",
            ).apply { addHeader("Location", it) }
        }
        faults[path]?.let { return status(it) }
        return when {
            path == "/index.php/login/v2" && session.method == Method.POST -> {
                loginStart()
            }

            path == "/index.php/login/v2/poll" && session.method == Method.POST -> {
                loginPoll()
            }

            path == "/ocs/v2.php/cloud/user" -> {
                authorized(session) { userInfo() }
            }

            path == "/ocs/v2.php/core/apppassword" && session.method == Method.DELETE -> {
                authorized(session) {
                    revoked = true
                    status(200)
                }
            }

            path.startsWith(DAV) -> {
                authorized(session) { dav(session, path.removePrefix(DAV)) }
            }

            else -> {
                status(404)
            }
        }
    }

    /** NanoHTTPD reads a body only for `POST` and `PUT` on request; what is left would be taken for the next request. */
    private fun drainBody(session: IHTTPSession) {
        var left = session.headers["content-length"]?.toLongOrNull() ?: 0L
        val buffer = ByteArray(4096)
        while (left > 0) {
            val n = session.inputStream.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) break
            left -= n
        }
    }

    private fun loginStart(): Response =
        json(
            """{"poll":{"token":"poll-token","endpoint":"$baseUrl/index.php/login/v2/poll"},"login":"$baseUrl/index.php/login/v2/flow/abc"}""",
        )

    private fun loginPoll(): Response =
        if (approved) {
            json("""{"server":"$baseUrl","loginName":"$loginName","appPassword":"$appPassword"}""")
        } else {
            status(404)
        }

    private fun userInfo() = json("""{"ocs":{"meta":{"status":"ok"},"data":{"id":"$userId"}}}""")

    private fun authorized(
        session: IHTTPSession,
        block: () -> Response,
    ): Response {
        val expected = "Basic " + Base64.getEncoder().encodeToString("$loginName:$appPassword".toByteArray())
        return if (session.headers["authorization"] == expected) block() else status(401)
    }

    private fun dav(
        session: IHTTPSession,
        rest: String,
    ): Response {
        val parts = rest.trimStart('/').split('/', limit = 2)
        if (parts[0] != userId) return status(404)
        // NanoHTTPD has decoded the path already.
        val path = parts.getOrElse(1) { "" }.trim('/')
        return when (session.method.name) {
            "PROPFIND" -> propfind(path)
            "GET" -> download(path)
            else -> status(405)
        }
    }

    private fun propfind(dir: String): Response {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val childFolders = LinkedHashSet<String>()
        val childFiles = ArrayList<Pair<String, Remote>>()
        for ((path, file) in files) {
            if (!path.startsWith(prefix)) continue
            val rest = path.removePrefix(prefix)
            if ('/' in rest) childFolders += rest.substringBefore('/') else childFiles += rest to file
        }
        for (folder in folders) {
            if (folder.startsWith(prefix) && folder != dir) childFolders += folder.removePrefix(prefix).substringBefore('/')
        }
        val exists = dir.isEmpty() || dir in folders || childFolders.isNotEmpty() || childFiles.isNotEmpty()
        if (!exists) return status(404)
        val xml = StringBuilder("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">""")
        xml.append(entry(prefix.trimEnd('/'), true, "", 0, 0))
        for (name in childFolders) xml.append(entry(prefix + name, true, "dir-$name", 0, 0))
        for ((name, file) in childFiles) {
            xml.append(
                entry(prefix + name, false, "\"${file.etag}\"", file.content.size.toLong(), file.modifiedMs),
            )
        }
        xml.append("</d:multistatus>")
        return newFixedLengthResponse(Response.Status.lookup(207), "application/xml; charset=utf-8", xml.toString())
    }

    private fun entry(
        path: String,
        folder: Boolean,
        etag: String,
        size: Long,
        modified: Long,
    ): String {
        val href = "$DAV/$userId/" + path.split('/').filter { it.isNotEmpty() }.joinToString("/") { encode(it) } + if (folder) "/" else ""
        val date =
            SimpleDateFormat(
                "EEE, dd MMM yyyy HH:mm:ss 'GMT'",
                Locale.US,
            ).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(modified)
        val props =
            if (folder) {
                "<d:resourcetype><d:collection/></d:resourcetype><d:getetag>$etag</d:getetag>"
            } else {
                "<d:resourcetype/><d:getetag>$etag</d:getetag><d:getcontentlength>$size</d:getcontentlength><d:getlastmodified>$date</d:getlastmodified>"
            }
        return "<d:response><d:href>$href</d:href><d:propstat><d:prop>$props</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
    }

    private fun download(path: String): Response {
        val file = files[path] ?: return status(404)
        if (downloadDelayMs > 0) {
            val stream =
                object : java.io.InputStream() {
                    private val inner = ByteArrayInputStream(file.content)
                    private var first = true

                    override fun read(): Int {
                        if (first) {
                            first = false
                            Thread.sleep(downloadDelayMs)
                        }
                        return inner.read()
                    }
                }
            return newFixedLengthResponse(Response.Status.OK, "application/octet-stream", stream, file.content.size.toLong())
        }
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/octet-stream",
            ByteArrayInputStream(file.content),
            file.content.size.toLong(),
        )
    }

    private fun json(body: String) = newFixedLengthResponse(Response.Status.OK, "application/json", body)

    private fun status(code: Int): Response =
        newFixedLengthResponse(Response.Status.lookup(code) ?: Response.Status.INTERNAL_ERROR, "text/plain", "")

    private fun encode(segment: String) = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private companion object {
        const val DAV = "/remote.php/dav/files"
    }
}
