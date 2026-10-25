package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.sources.RemoteFile
import io.github.tycho991.openframe.core.sources.RemoteFolder
import io.github.tycho991.openframe.core.sources.SourceException
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream

/**
 * A folder of a Nextcloud server over WebDAV. One `PROPFIND` with `Depth: 1` per folder, because Nextcloud refuses
 * `Depth: infinity`; subfolders are walked here. What differs between an account and a share is where the WebDAV
 * tree starts and how the frame identifies itself.
 */
abstract class DavFolder(
    protected val http: NextcloudHttp,
    protected val serverUrl: String,
    /** The configured folder as `/a/b` (`/` for the top). */
    private val folder: String,
) : RemoteFolder {
    /** The top of the WebDAV tree; the folder is below it. */
    protected abstract fun root(): HttpUrl.Builder

    /** The `Authorization` header. */
    protected abstract fun authorization(): String

    /**
     * Throws for an answer that is not a success; [what] names the thing asked for. A kind of folder can say more about
     * a refusal than the status code does.
     */
    protected open fun requireSuccess(
        response: Response,
        what: String,
    ) = http.requireSuccess(response, what)

    override fun list(recursive: Boolean): List<RemoteFile> {
        val files = ArrayList<RemoteFile>()
        walk("", recursive, files)
        return files
    }

    override fun download(
        file: RemoteFile,
        target: File,
    ) {
        val request =
            Request
                .Builder()
                .url(urlOf(file.path))
                .header("Authorization", authorization())
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        http.execute(request).use { response ->
            requireSuccess(response, "The file ${file.path}")
            val body =
                response.body ?: throw SourceException(SourceException.Kind.PROTOCOL, "The server sent no content for ${file.path}")
            try {
                body.byteStream().use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                        }
                    }
                }
            } catch (e: java.io.IOException) {
                target.delete()
                throw SourceException(SourceException.Kind.UNREACHABLE, "The download of ${file.path} broke off", e)
            }
        }
    }

    /** The entries of [relative] (empty for the folder itself) with the given `Depth`, the folder's own entry first. */
    protected fun propfind(
        relative: String,
        depth: Int,
    ): Pair<String, List<DavEntry>> {
        val url = urlOf(relative)
        val own = PathDecoding.decode(url.encodedPath).trimEnd('/')
        val body = PROPFIND_BODY.toRequestBody("application/xml".toMediaType())
        val request =
            Request
                .Builder()
                .url(url)
                .method("PROPFIND", body)
                .header("Depth", depth.toString())
                .header("Authorization", authorization())
                .header("User-Agent", USER_AGENT)
                .build()
        val entries =
            http.execute(request).use { response ->
                if (response.code != 207) {
                    val shown = "${folder.trimEnd('/')}/$relative".replace("//", "/").let { if (it.length > 1) it.trimEnd('/') else it }
                    requireSuccess(response, "The folder $shown")
                    throw SourceException(SourceException.Kind.PROTOCOL, "The folder was not listed (HTTP ${response.code})")
                }
                PropfindParser.parse(
                    response.body?.byteStream() ?: throw SourceException(SourceException.Kind.PROTOCOL, "Empty listing"),
                )
            }
        return own to entries
    }

    private fun walk(
        dir: String,
        recursive: Boolean,
        out: MutableList<RemoteFile>,
    ) {
        val (own, entries) = propfind(dir, 1)
        for (entry in entries) {
            // The first entry of a listing is the folder itself.
            if (entry.path == own) continue
            val path = if (dir.isEmpty()) entry.name else "$dir/${entry.name}"
            if (entry.isFolder) {
                if (recursive) walk(path, recursive, out)
            } else {
                out += RemoteFile(path, entry.etag, entry.size, entry.modifiedMs)
            }
        }
    }

    /** The URL of [relative] (a path below the configured folder, empty for the folder itself). */
    private fun urlOf(relative: String): HttpUrl {
        val builder = root()
        for (segment in folder.split('/').filter { it.isNotEmpty() }) builder.addPathSegment(segment)
        for (segment in relative.split('/').filter { it.isNotEmpty() }) builder.addPathSegment(segment)
        return builder.build()
    }

    protected companion object {
        const val USER_AGENT = "openframe"
        private const val BUFFER = 16 * 1024
        private const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop>""" +
                "<d:getetag/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/><d:displayname/></d:prop></d:propfind>"
    }
}

/** The folder of an account (`remote.php/dav/files/<user>/<folder>`), with the app password. */
class WebDavFolder(
    http: NextcloudHttp,
    serverUrl: String,
    private val userId: String,
    private val loginName: String,
    private val appPassword: String,
    folder: String,
) : DavFolder(http, serverUrl, folder) {
    override fun root(): HttpUrl.Builder =
        serverUrl
            .toHttpUrl()
            .newBuilder()
            .addPathSegments("remote.php/dav/files")
            .addPathSegment(userId)

    override fun authorization(): String = Credentials.basic(loginName, appPassword)
}

/** What the top of a share is. */
enum class ShareRoot { FOLDER, FILE }

/** The top of a share as one `PROPFIND` shows it: a folder or a file, and its name (empty if the server names none). */
data class ShareInfo(
    val root: ShareRoot,
    val name: String,
)

/**
 * A folder of a share over the public WebDAV of Nextcloud (`public.php/webdav/<folder>`), with the token as the
 * user name and the password of the share (empty for none). The token never goes into a URL.
 */
class ShareFolder(
    http: NextcloudHttp,
    serverUrl: String,
    private val token: String,
    private val password: String,
    folder: String,
) : DavFolder(http, serverUrl, folder) {
    override fun root(): HttpUrl.Builder =
        serverUrl
            .toHttpUrl()
            .newBuilder()
            .addPathSegments("public.php/webdav")

    override fun authorization(): String = Credentials.basic(token, password, Charsets.UTF_8)

    /** Reads the top of the share once (`PROPFIND` with `Depth: 0`), before the share is stored. Throws [SourceException]. */
    fun probe(): ShareInfo {
        val (_, entries) = propfind("", 0)
        val top = entries.firstOrNull() ?: throw SourceException(SourceException.Kind.PROTOCOL, "The share was not listed")
        return ShareInfo(if (top.isFolder) ShareRoot.FOLDER else ShareRoot.FILE, top.displayName)
    }

    /**
     * Nextcloud answers a token it does not know with the same `401` as a wrong password, and newer versions with a `404`
     * like a folder that is not there. The page of the share tells them apart: it does not exist (`404`) for a share that
     * was never there or was withdrawn. A "file drop" share, which only accepts uploads, refuses to be listed with `405`.
     */
    override fun requireSuccess(
        response: Response,
        what: String,
    ) {
        val code = response.code
        if (code == 405) {
            response.close()
            throw SourceException(
                SourceException.Kind.NOT_READABLE,
                "The share only accepts uploads (a file drop); the frame cannot read it",
            )
        }
        if (code != 401 && code != 403 && code != 404) return http.requireSuccess(response, what)
        response.close()
        if (shareIsGone()) {
            throw SourceException(
                SourceException.Kind.NOT_FOUND,
                "The share on ${serverUrl.toHttpUrl().host} was not found; it may have been withdrawn",
            )
        }
        if (code == 404) throw SourceException(SourceException.Kind.NOT_FOUND, "$what was not found (HTTP 404)")
        throw SourceException(SourceException.Kind.UNAUTHORIZED, "The share refused the password (HTTP $code)")
    }

    private fun shareIsGone(): Boolean {
        val url =
            serverUrl
                .toHttpUrl()
                .newBuilder()
                .addPathSegments("index.php/s")
                .addPathSegment(token)
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        return try {
            http.execute(request).use { it.code == 404 }
        } catch (e: SourceException) {
            // Without an answer the refusal stands as it is.
            false
        }
    }
}
