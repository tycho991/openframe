package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.Clock
import io.github.tycho991.openframe.core.platform.DecoderUnavailableException
import io.github.tycho991.openframe.core.platform.FileFacts
import io.github.tycho991.openframe.core.platform.FolderLister
import io.github.tycho991.openframe.core.platform.ImageException
import io.github.tycho991.openframe.core.platform.ImageInfo
import io.github.tycho991.openframe.core.platform.JvmFolderLister
import io.github.tycho991.openframe.core.platform.MediaEngine
import io.github.tycho991.openframe.core.platform.SkipReason
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
    /** Times each scan, on [Clock.monotonicMillis]. */
    private val clock: Clock = Clock.System,
    /** Lists the image files below [root] with their size and time (`PlatformInfo.folderLister`). */
    private val lister: FolderLister = JvmFolderLister,
) : PhotoSource {
    override val id = SOURCE_ID

    /** When a slow scan was last reported, on [Clock.monotonicMillis]; `null` before the first. */
    @Volatile private var slowReportedAt: Long? = null

    /** Whether the folder can be used now: its storage mounted, and the folder listable if it exists. */
    fun available(): Boolean = mounted(root) && (!root.exists() || root.list() != null)

    /**
     * Reconciles the index with the folder. A scan without a change does not edit the index (design decision 7 of
     * `library-performance`); one that takes longer than [SLOW_SCAN_MS] is reported at most once an hour, since the frame
     * scans every 15 s and the log files are small.
     */
    override suspend fun scan(): ScanResult {
        val started = clock.monotonicMillis()
        try {
            return scanFolder()
        } finally {
            val took = clock.monotonicMillis() - started
            if (took > SLOW_SCAN_MS) {
                val now = clock.monotonicMillis()
                val due = slowReportedAt.let { it == null || now - it >= HOUR_MS }
                if (due) {
                    slowReportedAt = now
                    log.w(TAG, "A scan of the photo folder took $took ms")
                }
            }
        }
    }

    private fun scanFolder(): ScanResult {
        if (!mounted(root)) {
            log.w(TAG, "The storage of the photo folder is not mounted; the scan changes nothing")
            return NOTHING
        }
        // A folder deleted over USB on mounted storage is created again, and its entries go below.
        root.mkdirs()
        // The index before the folder: a photo that enters the index while the folder is listed (an upload) is then
        // not one of the known entries, so its missing file in the listing cannot remove it (see the removal below).
        val library = index.library.value
        val listing = lister.list(root)
        if (listing == null) {
            log.w(TAG, "The photo folder cannot be listed; the scan changes nothing")
            return NOTHING
        }
        if (listing.unreadable > 0) log.w(TAG, "${listing.unreadable} entries of the photo folder could not be read")
        val known =
            library.photos.values
                .filter { it.source == id }
                .associateBy { it.path }
        val skippedKnown = library.skipped.filter { it.source == id }.associateBy { it.path }
        // The files of the listing that are still there; a file found gone when it was read is taken out (spec "Files
        // that vanish during a scan"), so its known entry is removed like that of any missing file.
        val present = HashMap(listing.files)
        val errors = ReadErrors()

        // Read outside the lock, compared with the listed facts only (spec "Cost of an unchanged scan"), and applied
        // in steps of at most STEP_MS (spec "Many new photos at once").
        val probed = LinkedHashMap<String, Result<PhotoEntry>>()
        var total = NOTHING
        var lastStep = clock.monotonicMillis()
        for ((path, facts) in listing.files) {
            val entry = known[path]
            if (entry != null && entry.fileSize == facts.size && entry.modifiedMs == facts.modifiedMs) continue
            val skip = skippedKnown[path]
            if (skip != null && skip.fileSize == facts.size && skip.modifiedMs == facts.modifiedMs) continue
            // A file the index did not know when the scan began may have been put in place since (a restore, an
            // upload): asked once more before it is opened, so a restored quarantined photo is not decoded.
            if (entry == null && placedFor(index.library.value.photos[PhotoEntry.idFor(id, path)], path, facts)) continue
            val result = runCatching { read(path, facts.file) }
            if (result.exceptionOrNull() is DecoderUnavailableException) {
                // Says nothing about the file; every further file would wait for the decoder as well.
                log.w(TAG, "The decoder is not available; the remaining files are checked at the next scan")
                break
            }
            if (result.isFailure && !facts.file.exists()) {
                present.remove(path)
                continue
            }
            probed[path] = result
            if (clock.monotonicMillis() - lastStep >= STEP_MS) {
                total += apply(probed, present, errors, known = null, skippedKnown = null)
                probed.clear()
                lastStep = clock.monotonicMillis()
            }
        }
        // Nothing new or changed, and every known file and every known skipped file still there: no edit, no new state.
        val missing = known.keys.any { it !in present } || skippedKnown.keys.any { it !in present }
        if (probed.isNotEmpty() || missing) total += apply(probed, present, errors, known, skippedKnown)
        errors.summarize()
        if (total.added + total.updated + total.removed + total.skipped > 0) log.i(TAG, "Scan: $total")
        return total
    }

    /**
     * Applies [probed] in one edit. With [known] and [skippedKnown], the scan's last step, it also removes the entries of
     * files that are no longer [present].
     */
    private fun apply(
        probed: Map<String, Result<PhotoEntry>>,
        present: Map<String, FileFacts>,
        errors: ReadErrors,
        known: Map<String, PhotoEntry>?,
        skippedKnown: Map<String, SkippedFile>?,
    ): ScanResult =
        index.edit { edit ->
            var added = 0
            var updated = 0
            var skipped = 0
            for ((path, result) in probed) {
                val facts = present.getValue(path)
                // An entry the frame has put in place while the folder was listed (a restore, an upload) for this very
                // file stays as it is, with its version, state and quarantine (spec "Restore during a scan"; design
                // decision 6 of `paired-device-guards`): the same size and time, as for an unchanged file above.
                if (placedFor(edit.get(PhotoEntry.idFor(id, path)), path, facts)) continue
                result
                    .onSuccess {
                        val current = edit.get(it.id)
                        if (current == null) added++ else updated++
                        // The version from the entry in the edit, so a file at the path of a removed photo gets
                        // one this ID never had (design decision 5 of `library-performance`).
                        edit.put(it.withStateOf(current).copy(version = edit.versionFor(current)))
                    }.onFailure { e ->
                        val reason = (e as? ImageException)?.reason ?: SkipReason.CORRUPT
                        if (e !is ImageException) errors.record(path, e)
                        edit.skip(SkippedFile(id, path, reason, facts.size, facts.modifiedMs))
                        skipped++
                    }
            }
            var removed = 0
            if (known != null && skippedKnown != null) {
                // Only entries exactly as they were when the scan began may be removed: a photo added meanwhile (an
                // upload, also under the name of a photo deleted meanwhile) is not in the file list taken earlier. An
                // edit keeps the objects of unchanged entries and every change creates a new one, so the identity
                // tells them apart.
                for (entry in edit.entries(id)) {
                    if (entry.path !in present && known[entry.path] === entry) {
                        edit.remove(entry.id)
                        removed++
                    }
                }
                for (skip in edit.skipped(id)) {
                    if (skip.path !in present && skippedKnown[skip.path] == skip) edit.unskip(id, skip.path)
                }
            }
            ScanResult(added, updated, removed, skipped)
        }

    private fun placedFor(
        placed: PhotoEntry?,
        path: String,
        facts: FileFacts,
    ) = placed != null && placed.path == path && placed.fileSize == facts.size && placed.modifiedMs == facts.modifiedMs

    /**
     * The unexpected read errors of one scan (spec "Files that vanish during a scan"): the first with its stack trace,
     * the others as one count, since every message names another file and the log would fold none of them.
     */
    private inner class ReadErrors {
        private var first = true
        private var more = 0

        fun record(
            path: String,
            e: Throwable,
        ) {
            if (first) {
                first = false
                log.w(TAG, "Error reading $path", e)
            } else {
                more++
            }
        }

        fun summarize() {
            if (more > 0) log.w(TAG, "$more more files could not be read (see the list of skipped files)")
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
        val entry = read(path, file, info)
        return index.edit { edit ->
            val current = edit.get(entry.id)
            entry.withStateOf(current).copy(version = edit.versionFor(current)).also(edit::put)
        }
    }

    /**
     * Accepts several files created by the app itself in one edit, an upload of several files, with the [ImageInfo] of
     * their checks. Throws [ImageException].
     */
    fun addAll(files: List<Pair<File, ImageInfo>>): List<PhotoEntry> {
        val read = files.map { (file, info) -> read(relativePath(root, file), file, info) }
        return index.edit { edit ->
            read.map { entry ->
                val current = edit.get(entry.id)
                entry.withStateOf(current).copy(version = edit.versionFor(current)).also(edit::put)
            }
        }
    }

    fun fileOf(entry: PhotoEntry): File = File(root, entry.path)

    /**
     * The entry of [file] at [path], probed unless [known] is given; its version is assigned when it is applied
     * ([LibraryEdit.versionFor]). Throws [ImageException].
     */
    internal fun read(
        path: String,
        file: File,
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
            takenAtMs = CaptureDates.plausible(info.takenAt)?.toEpochMilli() ?: mtime,
            fileSize = file.length(),
            modifiedMs = mtime,
            // Hidden state, comment and section are taken over when the entry is applied (PhotoEntry.withStateOf), and
            // the version is assigned there.
            version = 0,
            gps = info.gps,
            metadataVersion = PhotoEntry.METADATA_VERSION,
        )
    }

    companion object {
        const val SOURCE_ID = "local"
        private const val TAG = "scan"
        private const val SLOW_SCAN_MS = 1_000L
        private const val HOUR_MS = 60 * 60 * 1000L

        /** The longest time a scan reads files before it applies them (spec "Many new photos at once"). */
        private const val STEP_MS = 10_000L
        private val NOTHING = ScanResult(0, 0, 0, 0)

        private operator fun ScanResult.plus(other: ScanResult) =
            ScanResult(added + other.added, updated + other.updated, removed + other.removed, skipped + other.skipped)

        /** The path of an entry: scan and [add] must build it the same way, it is part of the photo's ID. */
        private fun relativePath(
            root: File,
            file: File,
        ) = file.relativeTo(root).invariantSeparatorsPath
    }
}
