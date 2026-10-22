package io.github.tycho991.openframe.core.nextcloud

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream

/**
 * The folder over WebDAV (`remote.php/dav/files/<user>/<folder>`). One `PROPFIND` with `Depth: 1` per folder,
 * because Nextcloud refuses `Depth: infinity`; subfolders are walked here.
 */
class WebDavFolder(
    private val http: NextcloudHttp,
    private val serverUrl: String,
    private val userId: String,
    private val loginName: String,
    private val appPassword: String,
    /** The configured folder as `/a/b` (`/` for the top). */
    private val folder: String,
) : RemoteFolder {
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
                .header("Authorization", auth())
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        http.execute(request).use { response ->
            http.requireSuccess(response, "The file ${file.path}")
            val body =
                response.body ?: throw NextcloudException(NextcloudException.Kind.PROTOCOL, "The server sent no content for ${file.path}")
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
                throw NextcloudException(NextcloudException.Kind.UNREACHABLE, "The download of ${file.path} broke off", e)
            }
        }
    }

    private fun walk(
        dir: String,
        recursive: Boolean,
        out: MutableList<RemoteFile>,
    ) {
        val url = urlOf(dir)
        val own = url.encodedPath.trimEnd('/')
        val body = PROPFIND_BODY.toRequestBody("application/xml".toMediaType())
        val request =
            Request
                .Builder()
                .url(url)
                .method("PROPFIND", body)
                .header("Depth", "1")
                .header("Authorization", auth())
                .header("User-Agent", USER_AGENT)
                .build()
        val entries =
            http.execute(request).use { response ->
                if (response.code != 207) {
                    http.requireSuccess(response, "The folder ${folder.trimEnd('/')}/$dir".replace("//", "/"))
                    throw NextcloudException(NextcloudException.Kind.PROTOCOL, "The folder was not listed (HTTP ${response.code})")
                }
                PropfindParser.parse(
                    response.body?.byteStream() ?: throw NextcloudException(NextcloudException.Kind.PROTOCOL, "Empty listing"),
                )
            }
        for (entry in entries) {
            // The first entry of a listing is the folder itself.
            if (entry.path ==
                java.net
                    .URI(own)
                    .path
                    .trimEnd('/')
            ) {
                continue
            }
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
        val builder =
            serverUrl
                .toHttpUrl()
                .newBuilder()
                .addPathSegments("remote.php/dav/files")
                .addPathSegment(userId)
        for (segment in folder.split('/').filter { it.isNotEmpty() }) builder.addPathSegment(segment)
        for (segment in relative.split('/').filter { it.isNotEmpty() }) builder.addPathSegment(segment)
        return builder.build()
    }

    private fun auth() = Credentials.basic(loginName, appPassword)

    private companion object {
        const val USER_AGENT = "openframe"
        const val BUFFER = 16 * 1024
        const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop>""" +
                "<d:getetag/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/></d:prop></d:propfind>"
    }
}
