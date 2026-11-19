package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.platform.ImageException
import io.github.tycho991.openframe.core.platform.SkipReason
import io.github.tycho991.openframe.core.readFully
import io.github.tycho991.openframe.core.sources.DownloadLimits
import io.github.tycho991.openframe.core.sources.RemoteFile
import io.github.tycho991.openframe.core.sources.RemoteFolder
import io.github.tycho991.openframe.core.sources.SourceException
import io.github.tycho991.openframe.core.sources.SourceLimits
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * A folder of a Nextcloud server over WebDAV. One `PROPFIND` with `Depth: 1` per folder, because Nextcloud refuses
 * `Depth: infinity`; subfolders are walked here. What differs between an account and a share is where the WebDAV
 * tree starts and how the frame identifies itself. Every answer is read within the limits of [SourceLimits].
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

    override fun list(
        recursive: Boolean,
        cancelled: () -> Boolean,
    ): List<RemoteFile> {
        val files = ArrayList<RemoteFile>()
        // A queue instead of recursion: the depth and the number of folders and files of a sync stay bounded.
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add("" to 0)
        var folders = 1
        while (queue.isNotEmpty()) {
            if (cancelled()) throw SourceException(SourceException.Kind.UNREACHABLE, "The listing was ended: the source was removed")
            val (dir, depth) = queue.removeFirst()
            for (entry in children(dir, cancelled)) {
                val path = if (dir.isEmpty()) entry.name else "$dir/${entry.name}"
                if (entry.isFolder) {
                    if (!recursive) continue
                    if (depth + 1 > SourceLimits.DEPTH) {
                        throw SourceException(
                            SourceException.Kind.LIMIT,
                            "The folder has more than ${SourceLimits.DEPTH} levels of subfolders",
                        )
                    }
                    if (++folders > SourceLimits.FOLDERS) {
                        throw SourceException(SourceException.Kind.LIMIT, "The folder has more than ${SourceLimits.FOLDERS} subfolders")
                    }
                    queue.add(path to depth + 1)
                } else {
                    if (files.size >= SourceLimits.FILES) {
                        throw SourceException(SourceException.Kind.LIMIT, "The folder has more than ${SourceLimits.FILES} files")
                    }
                    files += RemoteFile(path, entry.etag, entry.size, entry.modifiedMs)
                }
            }
        }
        return files
    }

    /**
     * The entries of [dir] without the folder itself, each checked to be a direct child of it (spec "Listings stay inside
     * the folder"). The reference is the folder's own entry: the one with the path asked for, else the first, which
     * Nextcloud always lists first. Anything else fails the whole listing, which promises all or nothing.
     */
    private fun children(
        dir: String,
        cancelled: () -> Boolean,
    ): List<DavEntry> {
        val (own, entries) = propfind(dir, 1, cancelled)
        val reference = entries.firstOrNull { it.path == own } ?: entries.firstOrNull() ?: return emptyList()
        val out = ArrayList<DavEntry>(entries.size)
        for (entry in entries) {
            if (entry === reference) continue
            val name = entry.name
            val parent = entry.path.substring(0, maxOf(0, entry.path.length - name.length - 1))
            if (name.isEmpty() || name == "." || name == ".." || parent != reference.path) {
                throw SourceException(
                    SourceException.Kind.PROTOCOL,
                    "The listing of the folder ${shown(dir)} names an entry outside of it; nothing of it is taken",
                )
            }
            out += entry
        }
        return out
    }

    override fun download(
        file: RemoteFile,
        target: File,
        limits: DownloadLimits,
    ): String? {
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
            val tolerance = SourceLimits.DOWNLOAD_TOLERANCE_BYTES
            val announced = body.contentLength()
            // A size of 0 is unknown: the listing had no `getcontentlength`.
            if (file.size > 0 && announced > file.size + tolerance) {
                throw SourceException(SourceException.Kind.CHANGED, "${file.path} was replaced on the server after the listing")
            }
            val cap =
                when {
                    file.size > 0 -> file.size + tolerance
                    announced >= 0 -> announced + tolerance
                    else -> limits.unknownSizeBytes
                }
            val room = limits.roomBytes()
            if (announced >
                room
            ) {
                throw SourceException(SourceException.Kind.STORAGE, "Not enough free storage on the frame for ${file.path}")
            }
            val stream =
                LimitedResponseStream(
                    body.byteStream(),
                    http.clock,
                    "The download of ${file.path}",
                    cap,
                    SourceLimits.DOWNLOAD_MS,
                    room,
                    limits.cancelled,
                )
            try {
                stream.use { input ->
                    // The first bytes tell the format; one that the device cannot decode is not downloaded further.
                    val header = ByteArray(HEADER_BYTES)
                    val n = input.readFully(header)
                    if (!limits.acceptHeader(header.copyOf(n))) {
                        throw ImageException(SkipReason.UNSUPPORTED, "The format of ${file.path} cannot be decoded on this device")
                    }
                    FileOutputStream(target).use { output ->
                        output.write(header, 0, n)
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
            } catch (e: ImageException) {
                target.delete()
                throw e
            } catch (e: IOException) {
                target.delete()
                if (LimitedResponseStream.failureIn(e) is LimitedResponseStream.LimitExceeded) {
                    val what = if (file.size > 0) "more than its listed size" else "more than the frame takes"
                    throw SourceException(SourceException.Kind.LIMIT, "The server sent $what for ${file.path}", e)
                }
                if (LimitedResponseStream.failureIn(e) == null) {
                    throw SourceException(SourceException.Kind.UNREACHABLE, "The download of ${file.path} broke off", e)
                }
                throw http.bodyFailure(e, request.url)
            } finally {
                lastDownloadBytes = stream.bytesRead
            }
            return response.header("ETag")
        }
    }

    /** The bytes the frame took from the body of the last download, for the tests. */
    @Volatile
    internal var lastDownloadBytes = 0L

    /** The entries of [relative] (empty for the folder itself) with the given `Depth`, and the path asked for. */
    protected fun propfind(
        relative: String,
        depth: Int,
        cancelled: () -> Boolean = { false },
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
                    requireSuccess(response, "The folder ${shown(relative)}")
                    throw SourceException(SourceException.Kind.PROTOCOL, "The folder was not listed (HTTP ${response.code})")
                }
                val stream = response.body?.byteStream() ?: throw SourceException(SourceException.Kind.PROTOCOL, "Empty listing")
                try {
                    PropfindParser.parse(
                        LimitedResponseStream(
                            stream,
                            http.clock,
                            "The listing of ${shown(relative)}",
                            SourceLimits.LISTING_BYTES,
                            SourceLimits.LISTING_MS,
                            cancelled = cancelled,
                        ),
                    )
                } catch (e: IOException) {
                    throw http.bodyFailure(e, url)
                }
            }
        return own to entries
    }

    /** [relative] as the user knows it: below the configured folder. */
    private fun shown(relative: String): String =
        "${folder.trimEnd('/')}/$relative".replace("//", "/").let { if (it.length > 1) it.trimEnd('/') else it }

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

        /** What the format of a file is told by. */
        private const val HEADER_BYTES = 32
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

    override fun authorization(): String = Credentials.basic(loginName, appPassword, Charsets.UTF_8)
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
                SourceException.Kind.GONE,
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
