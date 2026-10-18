package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.ImageException
import io.github.tycho991.openframe.core.platform.ImageFormats
import io.github.tycho991.openframe.core.platform.ImageInfo
import io.github.tycho991.openframe.core.platform.MediaEngine
import java.io.File

/**
 * Eine Quelle von Fotos. Jede Quelle verwaltet nur ihre eigenen Einträge im
 * Index. `local` jetzt, `nextcloud` in einem Folge-Change.
 */
interface PhotoSource {
    val id: String

    /** Gleicht den Index mit der Quelle ab. */
    suspend fun scan(): ScanResult
}

data class ScanResult(
    val added: Int,
    val updated: Int,
    val removed: Int,
    val skipped: Int,
)

/**
 * Der lokale Fotoordner, per USB erreichbar. Unveränderte Dateien erkennt der
 * Scan an `mtime` und Größe und öffnet sie nicht, auch übersprungene nicht.
 */
class LocalPhotoSource(
    val root: File,
    private val engine: MediaEngine,
    private val index: PhotoIndexStore,
    private val log: AppLog,
) : PhotoSource {
    override val id = SOURCE_ID

    override suspend fun scan(): ScanResult {
        root.mkdirs()
        val files = listFiles()
        val library = index.library.value
        val known =
            library.photos.values
                .filter { it.source == id }
                .associateBy { it.path }
        val skippedKnown = library.skipped.filter { it.source == id }.associateBy { it.path }

        // Erst außerhalb der Sperre lesen, dann in einem Schritt übernehmen.
        val probed = HashMap<String, Result<PhotoEntry>>()
        for ((path, file) in files) {
            val size = file.length()
            val mtime = file.lastModified()
            val entry = known[path]
            if (entry != null && entry.fileSize == size && entry.modifiedMs == mtime) continue
            val skip = skippedKnown[path]
            if (skip != null && skip.fileSize == size && skip.modifiedMs == mtime) continue
            probed[path] = runCatching { read(path, file, previous = entry) }
        }

        return index
            .edit { edit ->
                var added = 0
                var updated = 0
                var skipped = 0
                for ((path, result) in probed) {
                    val file = files.getValue(path)
                    result
                        .onSuccess {
                            if (edit.get(it.id) == null) added++ else updated++
                            edit.put(it)
                        }.onFailure { e ->
                            val reason = (e as? ImageException)?.reason ?: io.github.tycho991.openframe.core.platform.SkipReason.CORRUPT
                            if (e !is ImageException) log.w(TAG, "Fehler beim Lesen von $path", e)
                            edit.skip(SkippedFile(id, path, reason, file.length(), file.lastModified()))
                            skipped++
                        }
                }
                var removed = 0
                for (entry in edit.entries(id)) {
                    if (entry.path !in files) {
                        edit.remove(entry.id)
                        removed++
                    }
                }
                for (skip in edit.skipped(id)) {
                    if (skip.path !in files) edit.unskip(id, skip.path)
                }
                ScanResult(added, updated, removed, skipped)
            }.also {
                if (it.added + it.updated + it.removed + it.skipped > 0) log.i(TAG, "Scan: $it")
            }
    }

    /**
     * Nimmt eine selbst angelegte Datei sofort auf, etwa nach einem Upload.
     * Mit [info] aus einer vorigen Prüfung wird die Datei nicht erneut geöffnet. Wirft [ImageException].
     */
    fun add(
        file: File,
        info: ImageInfo? = null,
    ): PhotoEntry {
        val path = relativePath(file)
        val entry = read(path, file, previous = index.library.value.photos[PhotoEntry.idFor(id, path)], info)
        index.edit { it.put(entry) }
        return entry
    }

    fun fileOf(entry: PhotoEntry): File = File(root, entry.path)

    private fun read(
        path: String,
        file: File,
        previous: PhotoEntry?,
        known: ImageInfo? = null,
    ): PhotoEntry {
        val info = known ?: engine.probe(file)
        val mtime = file.lastModified()
        return PhotoEntry(
            id = PhotoEntry.idFor(id, path),
            source = id,
            path = path,
            width = info.width,
            height = info.height,
            takenAtMs = info.takenAt?.toEpochMilli() ?: mtime,
            fileSize = file.length(),
            modifiedMs = mtime,
            version = (previous?.version ?: 0) + 1,
            hidden = previous?.hidden ?: false,
        )
    }

    /** Alle Dateien mit Bild-Endung, versteckte Dateien und Ordner ausgenommen. */
    private fun listFiles(): Map<String, File> =
        root
            .walkTopDown()
            .onEnter { it == root || !it.name.startsWith('.') }
            .filter { it.isFile && !it.name.startsWith('.') && ImageFormats.hasImageExtension(it.name) }
            .associateBy { relativePath(it) }

    private fun relativePath(file: File) = file.relativeTo(root).invariantSeparatorsPath

    companion object {
        const val SOURCE_ID = "local"
        private const val TAG = "scan"
    }
}
