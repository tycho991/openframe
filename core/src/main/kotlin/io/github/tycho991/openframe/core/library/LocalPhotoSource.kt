package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.ImageException
import io.github.tycho991.openframe.core.platform.ImageFormats
import io.github.tycho991.openframe.core.platform.ImageInfo
import io.github.tycho991.openframe.core.platform.MediaEngine
import java.io.File

/**
 * A source of photos. Every source manages only its own entries in the
 * index: `local`, and each remote source with its own ID.
 */
interface PhotoSource {
    val id: String

    /** Reconciles the index with the source. */
    suspend fun scan(): ScanResult
}

data class ScanResult(
    val added: Int,
    val updated: Int,
    val removed: Int,
    val skipped: Int,
)

/**
 * The local photo folder, reachable over USB. The scan recognizes unchanged
 * files by `mtime` and size and does not open them, not even skipped ones.
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

        // Read outside the lock first, then apply in one step.
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
                            if (e !is ImageException) log.w(TAG, "Error reading $path", e)
                            edit.skip(SkippedFile(id, path, reason, file.length(), file.lastModified()))
                            skipped++
                        }
                }
                // Only entries that existed when the scan began may be removed: a photo added meanwhile
                // (an upload) is not in the file list taken earlier.
                var removed = 0
                for (entry in edit.entries(id)) {
                    if (entry.path !in files && entry.path in known) {
                        edit.remove(entry.id)
                        removed++
                    }
                }
                for (skip in edit.skipped(id)) {
                    if (skip.path !in files && skip.path in skippedKnown) edit.unskip(id, skip.path)
                }
                ScanResult(added, updated, removed, skipped)
            }.also {
                if (it.added + it.updated + it.removed + it.skipped > 0) log.i(TAG, "Scan: $it")
            }
    }

    /**
     * Accepts a file created by the app itself immediately, for example after an upload.
     * With [info] from a previous check the file is not opened again. Throws [ImageException].
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
            crop = Crop.carriedOver(previous, info.width, info.height),
        )
    }

    /** All files with an image extension, hidden files and folders excluded. */
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
