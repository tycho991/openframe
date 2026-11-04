package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.DecoderUnavailableException
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
 *
 * While the storage of the folder is not mounted or the folder cannot be listed, the scan changes nothing (spec
 * "Photo folder kept"): an empty listing then is not an empty folder.
 */
class LocalPhotoSource(
    val root: File,
    private val engine: MediaEngine,
    private val index: PhotoIndexStore,
    private val log: AppLog,
    /** Whether the storage that holds [root] is mounted (`PlatformInfo.storageMounted`). */
    private val mounted: (File) -> Boolean = { true },
    /** Lists the image files below [root]; replaced only by tests that act while the folder is listed. */
    private val listFiles: (File) -> Map<String, File> = { listImageFiles(it) },
) : PhotoSource {
    override val id = SOURCE_ID

    /** Whether the folder can be used now: its storage mounted, and the folder listable if it exists. */
    fun available(): Boolean = mounted(root) && (!root.exists() || root.list() != null)

    override suspend fun scan(): ScanResult {
        if (!mounted(root)) {
            log.w(TAG, "The storage of the photo folder is not mounted; the scan changes nothing")
            return NOTHING
        }
        // A folder deleted over USB on mounted storage is created again, and its entries go below.
        root.mkdirs()
        if (root.list() == null) {
            log.w(TAG, "The photo folder cannot be listed; the scan changes nothing")
            return NOTHING
        }
        // The index before the folder: a photo that enters the index while the folder is listed (an upload) is then
        // not one of the known entries, so its missing file in the listing cannot remove it (see the removal below).
        val library = index.library.value
        val files = listFiles(root)
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
            val result = runCatching { read(path, file, previous = entry) }
            if (result.exceptionOrNull() is DecoderUnavailableException) {
                // Says nothing about the file; every further file would wait for the decoder as well.
                log.w(TAG, "The decoder is not available; the remaining files are checked at the next scan")
                break
            }
            probed[path] = result
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
                            val current = edit.get(it.id)
                            if (current == null) added++ else updated++
                            edit.put(it.withStateOf(current))
                        }.onFailure { e ->
                            val reason = (e as? ImageException)?.reason ?: io.github.tycho991.openframe.core.platform.SkipReason.CORRUPT
                            if (e !is ImageException) log.w(TAG, "Error reading $path", e)
                            edit.skip(SkippedFile(id, path, reason, file.length(), file.lastModified()))
                            skipped++
                        }
                }
                // Only entries exactly as they were when the scan began may be removed: a photo added meanwhile (an
                // upload, also under the name of a photo deleted meanwhile) is not in the file list taken earlier. An
                // edit keeps the objects of unchanged entries and every change creates a new one, so the identity
                // tells them apart.
                var removed = 0
                for (entry in edit.entries(id)) {
                    if (entry.path !in files && known[entry.path] === entry) {
                        edit.remove(entry.id)
                        removed++
                    }
                }
                for (skip in edit.skipped(id)) {
                    if (skip.path !in files && skippedKnown[skip.path] == skip) edit.unskip(id, skip.path)
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
        val path = relativePath(root, file)
        val entry = read(path, file, previous = index.library.value.photos[PhotoEntry.idFor(id, path)], info)
        return index.edit { edit -> entry.withStateOf(edit.get(entry.id)).also(edit::put) }
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
            // Hidden state, comment and section are taken over when the entry is applied (PhotoEntry.withStateOf).
            version = (previous?.version ?: 0) + 1,
            gps = info.gps,
            metadataVersion = PhotoEntry.METADATA_VERSION,
        )
    }

    companion object {
        const val SOURCE_ID = "local"
        private const val TAG = "scan"
        private val NOTHING = ScanResult(0, 0, 0, 0)

        /** All files with an image extension below [root] by relative path, hidden files and folders excluded. */
        internal fun listImageFiles(root: File): Map<String, File> =
            root
                .walkTopDown()
                .onEnter { it == root || !it.name.startsWith('.') }
                .filter { it.isFile && !it.name.startsWith('.') && ImageFormats.hasImageExtension(it.name) }
                .associateBy { relativePath(root, it) }

        /** The path of an entry: scan and [add] must build it the same way, it is part of the photo's ID. */
        private fun relativePath(
            root: File,
            file: File,
        ) = file.relativeTo(root).invariantSeparatorsPath
    }
}
