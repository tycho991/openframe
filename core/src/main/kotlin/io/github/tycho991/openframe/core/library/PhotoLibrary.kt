package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.http.HttpException
import io.github.tycho991.openframe.core.http.HttpRequest
import io.github.tycho991.openframe.core.http.MultipartReader
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.ImageException
import io.github.tycho991.openframe.core.platform.ImageFormats
import io.github.tycho991.openframe.core.platform.ImageInfo
import io.github.tycho991.openframe.core.platform.MediaEngine
import io.github.tycho991.openframe.core.platform.MediaStoreNotifier
import io.github.tycho991.openframe.core.platform.PlatformInfo
import io.github.tycho991.openframe.core.readAtMost
import java.io.File
import java.util.UUID

/** Änderungen an der Bibliothek, die über die API oder die Anzeige kommen. */
class PhotoLibrary(
    val index: PhotoIndexStore,
    val local: LocalPhotoSource,
    val cache: RenderCache,
    private val engine: MediaEngine,
    private val notifier: MediaStoreNotifier,
    private val platform: PlatformInfo,
    private val log: AppLog,
) {
    fun setHidden(
        id: String,
        hidden: Boolean,
    ): PhotoEntry =
        index.edit { edit ->
            val entry = edit.get(id) ?: throw HttpException.notFound("Kein solches Foto")
            entry.copy(hidden = hidden).also(edit::put)
        }

    fun setAlbumHidden(
        album: String,
        hidden: Boolean,
    ) = index.edit { edit ->
        if (edit.photos.values.none { it.album == album }) throw HttpException.notFound("Kein solches Album")
        edit.setAlbumHidden(album, hidden)
    }

    /** Löscht Datei, Index-Eintrag und gerenderte Größen. `false`, wenn es das Foto nicht gibt. */
    fun delete(id: String): Boolean {
        val entry = index.library.value.photos[id] ?: return false
        val file = local.fileOf(entry)
        if (file.exists() && !file.delete()) throw HttpException(500, "delete_failed", "Datei ließ sich nicht löschen")
        index.edit { it.remove(id) }
        cache.remove(id)
        notifier.notifyChanged(file)
        log.i(TAG, "Gelöscht: ${entry.path}")
        return true
    }

    /**
     * Upload per `multipart/form-data` mit optionalem Feld `album` und Dateien
     * im Feld `file`. Gestreamt in einen versteckten Ordner im Fotoordner, erst
     * nach Prüfung aller Dateien verschoben. Ganz oder gar nicht.
     */
    fun upload(request: HttpRequest): List<PhotoEntry> {
        val boundary =
            MultipartReader.boundaryOf(request.header("content-type"))
                ?: throw HttpException(415, "unsupported_media_type", "Erwartet multipart/form-data")
        val root = local.root
        val staging = File(root, STAGING).apply { mkdirs() }
        request.contentLength?.let { length ->
            if (platform.freeBytes(root) - length < RESERVE_BYTES) throw insufficientStorage()
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
                        val tmp = File(staging, UUID.randomUUID().toString() + ".part")
                        staged += Staged(sanitizeName(part.filename), tmp)
                        copyWithSpaceCheck(part.body, tmp, root)
                    }

                    else -> {
                        part.body.skip(Long.MAX_VALUE)
                    }
                }
            }
            if (staged.isEmpty()) throw HttpException.badRequest("Keine Datei im Feld file")
            for (s in staged) s.info = check(s)
            return staged.map { move(it, album) }
        } finally {
            staged.forEach { it.tmp.delete() }
        }
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
            throw unsupported(s.name)
        }
    }

    private fun move(
        s: Staged,
        album: String?,
    ): PhotoEntry {
        val dir = if (album != null) File(local.root, album) else local.root
        dir.mkdirs()
        val info = s.info!!
        val name = if (ImageFormats.hasImageExtension(s.name)) s.name else "${s.name}.${info.format.extension}"
        // Gleichzeitige Uploads mit gleichem Namen dürfen sich nicht überschreiben.
        val target =
            synchronized(this) {
                uniqueFile(
                    dir,
                    name,
                ).also { if (!s.tmp.renameTo(it)) throw HttpException(500, "move_failed", "Datei ließ sich nicht ablegen") }
            }
        val entry = local.add(target, info)
        notifier.notifyChanged(target)
        log.i(TAG, "Hochgeladen: ${entry.path}")
        return entry
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
                    if (platform.freeBytes(root) < RESERVE_BYTES) throw insufficientStorage()
                }
            }
        }
        if (platform.freeBytes(root) < RESERVE_BYTES) throw insufficientStorage()
    }

    private fun insufficientStorage() =
        HttpException(507, "insufficient_storage", "Zu wenig Speicher: Nach dem Upload blieben weniger als 100 MB frei.")

    private fun unsupported(name: String) = HttpException(415, "unsupported_format", "$name hat kein unterstütztes Format")

    companion object {
        const val RESERVE_BYTES = 100L * 1024 * 1024
        private const val SPACE_CHECK_BYTES = 4L * 1024 * 1024
        private const val STAGING = ".upload"
        private const val TAG = "library"
        private const val FORBIDDEN = "<>:\"/\\|?*"

        /** Dateiname ohne Pfad und ohne Zeichen, die außerhalb des Zielordners führen oder Dateisysteme stören. */
        fun sanitizeName(raw: String): String {
            val base = raw.substringAfterLast('/').substringAfterLast('\\')
            val cleaned =
                base
                    .filter { it >= ' ' && it !in FORBIDDEN }
                    .trim()
                    .trimStart('.')
                    .trim()
            return cleaned.take(120).ifEmpty { "upload" }
        }

        /** Albumname als genau eine Ordnerebene direkt unterhalb des Fotoordners, `null` für kein Album. */
        fun sanitizeAlbum(raw: String): String? =
            raw
                .filter { it >= ' ' && it !in FORBIDDEN }
                .trim()
                .trimStart('.')
                .trim()
                .take(64)
                .ifEmpty { null }

        fun uniqueFile(
            dir: File,
            name: String,
        ): File {
            val stem = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.', "")
            var candidate = File(dir, name)
            var n = 1
            while (candidate.exists()) {
                candidate = File(dir, if (ext.isEmpty()) "$stem ($n)" else "$stem ($n).$ext")
                n++
            }
            return candidate
        }
    }
}
