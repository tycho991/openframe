package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.http.HttpException
import io.github.tycho991.openframe.core.http.HttpRequest
import io.github.tycho991.openframe.core.http.MultipartReader
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.DecoderUnavailableException
import io.github.tycho991.openframe.core.platform.ImageException
import io.github.tycho991.openframe.core.platform.ImageFormats
import io.github.tycho991.openframe.core.platform.ImageInfo
import io.github.tycho991.openframe.core.platform.MediaEngine
import io.github.tycho991.openframe.core.platform.MediaStoreNotifier
import io.github.tycho991.openframe.core.platform.PlatformInfo
import io.github.tycho991.openframe.core.platform.SkipReason
import io.github.tycho991.openframe.core.readAtMost
import java.io.File
import java.util.UUID

/** Changes to the library that come through the API or the display. */
class PhotoLibrary(
    val index: PhotoIndexStore,
    val local: LocalPhotoSource,
    val cache: RenderCache,
    private val engine: MediaEngine,
    private val notifier: MediaStoreNotifier,
    private val platform: PlatformInfo,
    private val log: AppLog,
    /** The storage reserve an upload leaves free ([StorageReserve]). */
    val reserveBytes: Long = StorageReserve.MARGIN_BYTES,
) {
    fun setHidden(
        id: String,
        hidden: Boolean,
    ): PhotoEntry = update(id) { it.copy(hidden = hidden) }

    /**
     * Changes the photo's state in one step, so a patch of several fields (hidden state, section) is one change of the
     * library. Allowed for every source: nothing is written to it.
     */
    fun update(
        id: String,
        change: (PhotoEntry) -> PhotoEntry,
    ): PhotoEntry =
        index.edit { edit ->
            val entry = edit.get(id) ?: throw HttpException.notFound("No such photo")
            change(entry).also(edit::put)
        }

    fun setAlbumHidden(
        album: String,
        hidden: Boolean,
    ) = index.edit { edit ->
        // Inside edit the state does not change, and its album list is computed once per state anyway.
        if (album !in index.library.value.albums) throw HttpException.notFound("No such album")
        edit.setAlbumHidden(album, hidden)
    }

    /** What a batch did with each ID: every ID ends in exactly one of the lists (spec "Batch changes"). */
    class BatchResult(
        val processed: List<String>,
        val missing: List<String>,
        val failed: List<String>,
    )

    /** Deletes a file; a test makes it fail. */
    @Volatile
    internal var deleteFile: (File) -> Boolean = { it.delete() }

    /**
     * Hides or shows the photos with [ids] in one edit, so the library version changes at most once (design decision 8
     * of `library-performance`). Unknown IDs are missing; a photo already in that state counts as processed.
     */
    fun setHiddenAll(
        ids: Collection<String>,
        hidden: Boolean,
    ): BatchResult {
        val processed = ArrayList<String>()
        val missing = ArrayList<String>()
        index.edit { edit ->
            for (id in ids.distinct()) {
                val entry = edit.get(id)
                if (entry == null) {
                    missing += id
                } else {
                    if (entry.hidden != hidden) edit.put(entry.copy(hidden = hidden))
                    processed += id
                }
            }
        }
        return BatchResult(processed, missing, emptyList())
    }

    /**
     * Deletes the files of the photos with [ids] and removes them in one edit, with one report to the media store; the
     * renderings go through the index's notification. Unknown IDs are missing; a photo of a remote source, which would
     * come back with the next sync, and a file that cannot be deleted are failed; a file already gone counts as deleted.
     * While the photo folder is unavailable a missing file says nothing, so nothing is deleted: 503
     * `storage_unavailable` (spec "Photo folder kept").
     */
    fun deleteAll(ids: Collection<String>): BatchResult {
        if (!local.available()) throw storageUnavailable()
        val library = index.library.value
        val deleted = ArrayList<PhotoEntry>()
        val missing = ArrayList<String>()
        val failed = ArrayList<String>()
        for (id in ids.distinct()) {
            val entry = library.photos[id]
            when {
                entry == null -> missing += id
                entry.source != LocalPhotoSource.SOURCE_ID -> failed += id
                local.fileOf(entry).let { !it.exists() || runCatching { deleteFile(it) }.getOrDefault(false) } -> deleted += entry
                else -> failed += id
            }
        }
        if (deleted.isNotEmpty()) {
            index.edit { edit -> deleted.forEach { edit.remove(it.id) } }
            notifier.notifyChanged(deleted.map(local::fileOf))
            if (deleted.size == 1) log.i(TAG, "Deleted: ${deleted.single().path}") else log.i(TAG, "Deleted: ${deleted.size} photos")
        }
        return BatchResult(deleted.map { it.id }, missing, failed)
    }

    /**
     * Deletes file, index entry and rendered sizes: [deleteAll] of one ID. `false` if the photo does not exist, 409 for a
     * photo of a read-only source, 500 if its file cannot be deleted, 503 while the photo folder is unavailable.
     */
    fun delete(id: String): Boolean {
        val result = deleteAll(listOf(id))
        if (result.missing.isNotEmpty()) return false
        if (result.failed.isEmpty()) return true
        // A photo that the frame only reads would come back with the next sync.
        if (index.library.value.photos[id]
                ?.source != LocalPhotoSource.SOURCE_ID
        ) {
            throw HttpException(
                409,
                "read_only_source",
                "This photo comes from a remote source and cannot be deleted here. Hide it instead.",
            )
        }
        throw HttpException(500, "delete_failed", "File could not be deleted")
    }

    private fun storageUnavailable() =
        HttpException(
            503,
            "storage_unavailable",
            "The photo folder is not available; is the storage mounted?",
        )

    /**
     * Upload by `multipart/form-data` with an optional field `album` and files
     * in the field `file`. Streamed into a hidden folder inside the photo folder, moved
     * only after all files have been checked. All or nothing.
     */
    fun upload(request: HttpRequest): List<PhotoEntry> {
        val boundary =
            MultipartReader.boundaryOf(request.header("content-type"))
                ?: throw HttpException(415, "unsupported_media_type", "Expected multipart/form-data")
        val root = local.root
        if (!local.available()) throw storageUnavailable()
        val staging = File(root, STAGING).apply { mkdirs() }
        request.contentLength?.let { length ->
            if (platform.freeBytes(root) - length < reserveBytes) throw insufficientStorage()
        }
        val staged = mutableListOf<Staged>()
        var album: String? = null
        try {
            val reader = MultipartReader(request.bodyStream(), boundary)
            while (true) {
                val part = reader.next() ?: break
                when {
                    part.name == "album" && part.filename == null -> {
                        album = sanitizeAlbum(part.body.readAtMost(1024).toString(Charsets.UTF_8))
                    }

                    part.name == "file" && part.filename != null -> {
                        val tmp = File(staging, UUID.randomUUID().toString() + PART)
                        staged += Staged(sanitizeName(part.filename), tmp)
                        copyWithSpaceCheck(part.body, tmp, root)
                    }

                    else -> {
                        part.body.skip(Long.MAX_VALUE)
                    }
                }
            }
            if (staged.isEmpty()) throw HttpException.badRequest("No file in the field file")
            for (s in staged) s.info = check(s)
            return moveAll(staged, album)
        } finally {
            staged.forEach { it.tmp.delete() }
        }
    }

    /**
     * Deletes the partial files of uploads that a crash, a kill or an update interrupted (spec "Partial uploads after
     * a crash"). At the start, before the server accepts uploads.
     */
    fun removePartialUploads() {
        if (!local.available()) return
        val parts = File(local.root, STAGING).listFiles { f -> f.isFile && f.name.endsWith(PART) }.orEmpty()
        val removed = parts.count { it.delete() }
        if (removed > 0) log.i(TAG, "$removed partial uploads removed")
    }

    private class Staged(
        val name: String,
        val tmp: File,
    ) {
        var info: ImageInfo? = null
    }

    private fun check(s: Staged): ImageInfo {
        if (ImageFormats.detect(s.tmp) == null) throw unsupported(s.name)
        return try {
            engine.probe(s.tmp)
        } catch (e: ImageException) {
            if (e.reason == SkipReason.TOO_LARGE) throw HttpException(415, "image_too_large", "${s.name} is too large: ${e.message}")
            if (e.reason == SkipReason.CRASHED) log.w(TAG, "Upload of ${s.name} refused: it made the decoder crash")
            throw unsupported(s.name)
        } catch (e: DecoderUnavailableException) {
            throw HttpException(503, "decoder_unavailable", "The decoder is not available; try again later")
        }
    }

    /** Renames a staged file into place; a test makes it fail. */
    @Volatile
    internal var renameFile: (File, File) -> Boolean = { from, to -> from.renameTo(to) }

    /**
     * Puts all [staged] files in place, all or nothing (design decision 9 of `library-performance`): under one lock the
     * targets of all files are resolved, also against each other, and then all are renamed; if one rename fails, the
     * ones already renamed are deleted before anything is indexed. Then one edit adds all of them, and one report goes
     * to the media store.
     */
    private fun moveAll(
        staged: List<Staged>,
        album: String?,
    ): List<PhotoEntry> {
        // Concurrent uploads with the same name must not overwrite each other.
        val targets =
            synchronized(this) {
                val dir = albumFolder(album)
                val taken = HashSet<String>()
                val targets =
                    staged.map { s ->
                        val name = if (ImageFormats.hasImageExtension(s.name)) s.name else "${s.name}.${s.info!!.format.extension}"
                        // Compared without letter case: the photo folder of the reference devices does not tell it apart.
                        uniqueFile(dir, name) { it.exists() || it.name.lowercase() in taken }.also { taken += it.name.lowercase() }
                    }
                val moved = ArrayList<File>()
                for ((s, target) in staged.zip(targets)) {
                    if (!renameFile(s.tmp, target)) {
                        moved.forEach { it.delete() }
                        throw HttpException(500, "move_failed", "File could not be put in place")
                    }
                    moved += target
                }
                targets
            }
        val entries = local.addAll(staged.zip(targets).map { (s, target) -> target to s.info!! })
        notifier.notifyChanged(targets)
        entries.forEach { log.i(TAG, "Uploaded: ${it.path}") }
        return entries
    }

    /**
     * The folder of [album], created if needed. Each level goes into an existing folder of that name, the same name
     * first and then one that differs only in letter case (spec "Upload into an album in another letter case"), so
     * the index path matches the folder on the frame's file system. A level that exists as a file is 400
     * `invalid_album`.
     */
    private fun albumFolder(album: String?): File {
        var dir = local.root
        if (album != null) {
            for (level in album.split('/')) {
                val exact = File(dir, level)
                dir =
                    if (exact.exists()) {
                        exact
                    } else {
                        dir.listFiles { f -> f.isDirectory && f.name.equals(level, ignoreCase = true) }?.firstOrNull() ?: exact
                    }
            }
        }
        dir.mkdirs()
        if (!dir.isDirectory) {
            throw HttpException(
                400,
                "invalid_album",
                "The album path is invalid",
                mapOf("album" to "A level of the album path is a file in the photo folder"),
            )
        }
        return dir
    }

    private fun copyWithSpaceCheck(
        input: java.io.InputStream,
        target: File,
        root: File,
    ) {
        val buf = ByteArray(64 * 1024)
        var sinceCheck = 0L
        target.outputStream().use { out ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                sinceCheck += n
                if (sinceCheck >= SPACE_CHECK_BYTES) {
                    sinceCheck = 0
                    if (platform.freeBytes(root) < reserveBytes) throw insufficientStorage()
                }
            }
        }
        if (platform.freeBytes(root) < reserveBytes) throw insufficientStorage()
    }

    private fun insufficientStorage() =
        HttpException(
            507,
            "insufficient_storage",
            "Not enough storage: less than the storage reserve of ${reserveBytes / StorageReserve.MIB} MiB would remain free after the upload.",
        )

    private fun unsupported(name: String) = HttpException(415, "unsupported_format", "$name has no supported format")

    companion object {
        private const val SPACE_CHECK_BYTES = 4L * 1024 * 1024
        private const val STAGING = ".upload"
        private const val PART = ".part"
        private const val TAG = "library"
        private const val FORBIDDEN = "<>:\"/\\|?*"

        /** The most bytes of UTF-8 an uploaded file's name keeps, below the limit of 255 for ` (n)` and an extension. */
        const val MAX_NAME_BYTES = 200

        /**
         * File name without path and without characters that lead outside the target folder or upset file systems, at
         * most [MAX_NAME_BYTES] bytes of UTF-8 with its image extension kept, cut at code point boundaries (design
         * decision 9 of `library-performance`). A long extension of another kind counts as part of the stem.
         */
        fun sanitizeName(raw: String): String {
            val base = cleanLevel(raw.substringAfterLast('/').substringAfterLast('\\'))
            val extension = if (ImageFormats.hasImageExtension(base)) "." + base.substringAfterLast('.') else ""
            val stem = cutToBytes(base.dropLast(extension.length), MAX_NAME_BYTES - utf8Bytes(extension)).trimEnd()
            return if (stem.isEmpty()) "upload$extension" else stem + extension
        }

        /** [text] cut at a code point boundary to at most [maxBytes] bytes of UTF-8. */
        internal fun cutToBytes(
            text: String,
            maxBytes: Int,
        ): String {
            var bytes = 0
            var i = 0
            while (i < text.length) {
                val codePoint = text.codePointAt(i)
                bytes += utf8Bytes(codePoint)
                if (bytes > maxBytes) break
                i += Character.charCount(codePoint)
            }
            return text.substring(0, i)
        }

        private fun utf8Bytes(codePoint: Int): Int =
            when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }

        private fun utf8Bytes(text: String): Int {
            var bytes = 0
            var i = 0
            while (i < text.length) {
                val codePoint = text.codePointAt(i)
                bytes += utf8Bytes(codePoint)
                i += Character.charCount(codePoint)
            }
            return bytes
        }

        /** [text] cut to at most [n] UTF-16 units, but never inside a surrogate pair. */
        private fun takeUnits(
            text: String,
            n: Int,
        ): String {
            if (text.length <= n) return text
            val end = if (Character.isHighSurrogate(text[n - 1])) n - 1 else n
            return text.substring(0, end)
        }

        /**
         * One folder level or file name without forbidden characters and without leading dots, so it
         * is neither hidden nor `.` or `..`. Dots and spaces are trimmed together: `.. ..` is empty.
         */
        private fun cleanLevel(raw: String): String =
            raw
                .filter { it >= ' ' && it !in FORBIDDEN }
                .trimStart { it == '.' || it.isWhitespace() }
                .trimEnd()

        const val MAX_ALBUM_PATH = 255

        /**
         * Album path below the photo folder, `null` for no album. Each level is sanitized on its own,
         * so empty levels, `.` and `..` disappear; more than [MAX_ALBUM_PATH] characters is 400.
         */
        fun sanitizeAlbum(raw: String): String? {
            val path =
                raw
                    .split('/')
                    .map { takeUnits(cleanLevel(it), 64).trimEnd() }
                    .filter { it.isNotEmpty() }
                    .joinToString("/")
            if (path.length > MAX_ALBUM_PATH) {
                throw HttpException(
                    400,
                    "invalid_album",
                    "The album path is too long",
                    mapOf("album" to "At most $MAX_ALBUM_PATH characters"),
                )
            }
            return path.ifEmpty { null }
        }

        /** [name] in [dir], or `name (n).ext` with the first `n` for which [taken] is false. */
        fun uniqueFile(
            dir: File,
            name: String,
            taken: (File) -> Boolean = { it.exists() },
        ): File {
            val stem = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.', "")
            var candidate = File(dir, name)
            var n = 1
            while (taken(candidate)) {
                candidate = File(dir, if (ext.isEmpty()) "$stem ($n)" else "$stem ($n).$ext")
                n++
            }
            return candidate
        }
    }
}
