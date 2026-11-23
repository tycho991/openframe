package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.Clock
import io.github.tycho991.openframe.core.platform.ImageFormats
import io.github.tycho991.openframe.core.store.JsonStore
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Locale

/** What the frame knows of a trash entry beyond its files: the photo's entry at the deletion and the deleting device. */
@Serializable
data class TrashRecord(
    val trashId: String,
    /** The whole index entry at the deletion: version, hidden state, comment, section, quarantine, position. */
    val entry: PhotoEntry,
    /** The ID of the device that deleted it, `null` if unknown. */
    val deletedBy: String? = null,
)

/** `trash.json`, keyed by trash ID. */
@Serializable
data class StoredTrash(
    val records: List<TrashRecord> = emptyList(),
)

/**
 * One entry of the trash as the tree has it (design decision 2 of `paired-device-guards`): a real folder
 * `.trash/<trashId>` with the photo's file below its relative path, and the record if there is one.
 */
class TrashItem(
    val id: String,
    /** When it was deleted, from its ID, on the wall clock. */
    val deletedAtMs: Long,
    /** The photo's path below the photo folder, from the tree; `null` for a damaged entry. */
    val path: String?,
    /** The one image file of the entry; `null` for a damaged entry. */
    val file: File?,
    /** The bytes of every file of the entry, symbolic links not followed. */
    val bytes: Long,
    /** None or several image files: listed, purged by age, never restored. */
    val damaged: Boolean,
    val record: TrashRecord?,
) {
    /** The photo's former ID, derived from its path as for any local photo; `null` for a damaged entry. */
    val photoId: String? get() = path?.let { PhotoEntry.idFor(LocalPhotoSource.SOURCE_ID, it) }

    /** The photo crashed the decoder before it was deleted, or its trash thumbnail did. */
    val quarantined: Boolean get() = record?.entry?.quarantined == true

    /**
     * The entry the render cache renders the thumbnail from: a copy of the saved one with the ID `trash-<trashId>` and
     * the source [SOURCE] (design decision 10). `null` when it must never be rendered: damaged, without a record (its
     * quarantine is unknown), or quarantined.
     */
    val thumbEntry: PhotoEntry?
        get() {
            val saved = record?.entry ?: return null
            if (damaged || path == null || saved.quarantined) return null
            return saved.copy(id = thumbId(id), source = SOURCE)
        }

    /** The entry whose renderings [TrashStore] removes on purge and restore; also for an entry that had none. */
    internal val renderings: PhotoEntry?
        get() = record?.entry?.copy(id = thumbId(id), source = SOURCE)

    companion object {
        /** The source of the entries that render trash thumbnails. */
        const val SOURCE = "trash"

        fun thumbId(trashId: String) = "trash-$trashId"
    }
}

/**
 * The trash for deleted local photos (spec `photo-library`, "Trash for deleted photos"; design decisions 2, 3, 8 and 9
 * of `paired-device-guards`): the hidden folder `.trash` in the photo folder, which the scan skips, holds one folder per
 * entry; the file tree is the source of truth, and `trash.json` keeps what the tree cannot: the photo's state and the
 * deleting device.
 *
 * The mirror of the tree is built on the core's scope after the server started and again at each purge check; every
 * operation waits for the first build. Only real folders named like an entry count: everything else in `.trash` is never
 * listed, moved or deleted, and no symbolic link is followed. Lock order: library → trash → index → cache; this class
 * never calls the index or the cache under its lock.
 */
class TrashStore(
    /** The photo folder; the trash is its folder [FOLDER]. */
    val photoRoot: File,
    private val store: JsonStore<StoredTrash>,
    private val clock: Clock,
    private val log: AppLog,
    /** Whether the photo folder can be used now ([LocalPhotoSource.available]). */
    private val available: () -> Boolean = { true },
    private val freeBytes: (File) -> Long = { it.usableSpace },
    private val totalBytes: (File) -> Long = { it.totalSpace },
    /** Removes the renderings of trash thumbnails; called outside the trash's lock. */
    private val removeRenderings: (List<PhotoEntry>) -> Unit = {},
    private val random: SecureRandom = SecureRandom(),
) {
    val root: File = File(photoRoot, FOLDER)

    private var built = false
    private var items: Map<String, TrashItem> = emptyMap()
    private var records: Map<String, TrashRecord> = emptyMap()

    /** Renames a file into or out of the trash; a test makes it fail. */
    @Volatile
    internal var renameFile: (File, File) -> Boolean = { from, to -> from.renameTo(to) }

    /** Loads `trash.json`. The tree is read later, by [ensureBuilt]. */
    @Synchronized
    fun load() {
        records =
            store
                .load()
                ?.records
                .orEmpty()
                .filter { PATTERN.matches(it.trashId) }
                .associateBy { it.trashId }
    }

    /** Builds the mirror if that has not happened yet; every operation calls it first. */
    @Synchronized
    fun ensureBuilt() {
        if (built) return
        buildLocked()
        if (built && items.isNotEmpty()) log.i(TAG, "Trash: ${items.size} entries, ${megabytes(items.values.sumOf { it.bytes })} MB")
    }

    /** The entries, newest first. */
    @Synchronized
    fun items(): List<TrashItem> {
        ensureBuilt()
        return items.values.sortedWith(compareByDescending<TrashItem> { it.deletedAtMs }.thenByDescending { it.id })
    }

    @Synchronized
    fun get(id: String): TrashItem? {
        if (!PATTERN.matches(id)) return null
        ensureBuilt()
        return items[id]
    }

    /**
     * Moves [file], the file of the local photo [entry], into a new entry (design decision 4): its folder with the
     * album levels, the record, then the rename. `false` if that fails; then nothing of it stays.
     */
    @Synchronized
    fun moveIn(
        entry: PhotoEntry,
        file: File,
        deletedBy: String?,
    ): Boolean {
        ensureBuilt()
        if (!prepareRoot()) return false
        val id = newId(clock.millis())
        val folder = File(root, id)
        val target = File(folder, entry.path)
        // The path of an index entry never leaves the photo folder, but a trash entry must not either.
        if (!inside(target, folder)) return false
        val record = TrashRecord(id, entry, deletedBy)
        val moved =
            try {
                target.parentFile.mkdirs() && putRecord(record) && renameFile(file, target)
            } catch (e: Exception) {
                log.w(TAG, "Moving ${entry.path} to the trash failed", e)
                false
            }
        if (!moved) {
            dropRecords(listOf(id))
            deleteTree(folder)
            return false
        }
        items = items + (id to TrashItem(id, deletedAtOf(id), entry.path, target, target.length(), false, record))
        return true
    }

    /**
     * Runs [block] under the trash's lock with the mirror built; [PhotoLibrary.restore] holds it around its edit of the
     * index, after the library's lock.
     */
    @Synchronized
    fun <R> locked(block: () -> R): R {
        ensureBuilt()
        return block()
    }

    /**
     * Removes the restored entries [ids]: their records, what is left of their folders, and their trash thumbnails. Under
     * [locked]; the thumbnails go through [removeRenderings] once the caller has left the lock ([takeRenderings]).
     */
    @Synchronized
    internal fun restored(ids: Collection<String>): List<PhotoEntry> {
        val gone = ids.mapNotNull { items[it] }
        for (item in gone) deleteTree(File(root, item.id))
        items = items - ids.toSet()
        dropRecords(ids)
        return gone.mapNotNull { it.renderings }
    }

    /** Hands [entries] to [removeRenderings]; never under the trash's lock. */
    internal fun takeRenderings(entries: List<PhotoEntry>) {
        if (entries.isNotEmpty()) removeRenderings(entries)
    }

    /**
     * The decoder crashed on the thumbnail of [thumb] (design decision 10): its record is marked quarantined, so it is
     * never rendered again and a restore gives a quarantined photo. Ignored for an entry that changed meanwhile.
     */
    fun quarantine(thumb: PhotoEntry) {
        synchronized(this) {
            val id = thumb.id.removePrefix("trash-")
            val record = records[id] ?: return
            if (record.entry.version != thumb.version) return
            val marked = record.copy(entry = record.entry.copy(quarantinedVersion = record.entry.version))
            if (!putRecord(marked)) return
            items[id]?.let { items = items + (id to it.withRecord(marked)) }
        }
        log.w(TAG, "The thumbnail of trash entry ${thumb.id.removePrefix("trash-")} made the decoder crash; it is quarantined")
    }

    /** The file of the trash entry that [thumb] renders, for the render cache. */
    fun fileOf(thumb: PhotoEntry): File? = get(thumb.id.removePrefix("trash-"))?.file

    /**
     * The purge check (design decision 8), after the first build and then hourly, also in safe mode: the mirror is built
     * again from the tree, an entry stamped before [TRASH_CLOCK_FLOOR] or more than an hour ahead is re-stamped once the
     * clock is past the floor, and entries 7 days old go. While the clock is below the floor nothing goes by age.
     */
    fun purge() {
        val removed: List<PhotoEntry>
        val count: Int
        val bytes: Long
        synchronized(this) {
            if (!available()) return
            buildLocked()
            val now = clock.millis()
            if (now < TRASH_CLOCK_FLOOR) return
            restampLocked(now)
            val due = items.values.filter { now - it.deletedAtMs >= RETENTION_MS }
            removed = deleteLocked(due)
            count = due.size
            bytes = due.sumOf { it.bytes }
        }
        takeRenderings(removed)
        if (count > 0) log.i(TAG, "Trash purged: $count entries, ${megabytes(bytes)} MB")
    }

    /**
     * Frees storage for [neededFreeBytes] free in [dir] under storage pressure (design decision 9): only entries deleted
     * more than [PRESSURE_AGE_MS] ago, the oldest first, and only if all of them together reach the need; otherwise none
     * goes and the answer is `false`. Only for a [dir] on the trash's own volume. Takes only the trash's lock.
     */
    fun makeRoom(
        dir: File,
        neededFreeBytes: Long,
    ): Boolean {
        val removed: List<PhotoEntry>
        val gone: List<TrashItem>
        synchronized(this) {
            val free = freeBytes(dir)
            if (free >= neededFreeBytes) return true
            if (!available() || !sameVolume(dir, free)) return false
            ensureBuilt()
            val eligible = eligibleLocked()
            if (free + eligible.sumOf { it.bytes } < neededFreeBytes) return false
            var gained = 0L
            gone = eligible.takeWhile { item -> (free + gained < neededFreeBytes).also { if (it) gained += item.bytes } }
            removed = deleteLocked(gone)
        }
        takeRenderings(removed)
        if (gone.isNotEmpty()) log.i(TAG, "Trash purged: ${gone.size} entries, ${megabytes(gone.sumOf { it.bytes })} MB, for storage")
        return freeBytes(dir) >= neededFreeBytes
    }

    /**
     * The bytes [makeRoom] could free for [dir] now, deleting nothing: for the announced length of an upload, which must
     * not make the frame delete entries for a body that never comes.
     */
    @Synchronized
    fun reclaimableBytes(dir: File): Long {
        if (!available() || !sameVolume(dir, freeBytes(dir))) return 0
        ensureBuilt()
        return eligibleLocked().sumOf { it.bytes }
    }

    suspend fun flush() = store.flush()

    /** Under the lock: entries deleted more than a day ago by a clock past the floor, oldest first. */
    private fun eligibleLocked(): List<TrashItem> {
        val now = clock.millis()
        if (now < TRASH_CLOCK_FLOOR) return emptyList()
        return items.values
            .filter { it.deletedAtMs >= TRASH_CLOCK_FLOOR && it.deletedAtMs <= now && now - it.deletedAtMs > PRESSURE_AGE_MS }
            .sortedWith(compareBy<TrashItem> { it.deletedAtMs }.thenBy { it.id })
    }

    /**
     * Without `java.nio.file` on API 21, [dir] counts as the trash's volume when its total and free bytes equal those
     * of the photo folder, read together (design decision 9).
     */
    private fun sameVolume(
        dir: File,
        free: Long,
    ): Boolean = totalBytes(dir) == totalBytes(photoRoot) && free == freeBytes(photoRoot)

    /** Under the lock: deletes [due] with their folders and records; returns the entries whose renderings go. */
    private fun deleteLocked(due: List<TrashItem>): List<PhotoEntry> {
        if (due.isEmpty()) return emptyList()
        for (item in due) deleteTree(File(root, item.id))
        val ids = due.map { it.id }
        items = items - ids.toSet()
        dropRecords(ids)
        return due.mapNotNull { it.renderings }
    }

    /** Under the lock: gives entries with an implausible time the current time and a new ID (design decision 8). */
    private fun restampLocked(now: Long) {
        val wrong = items.values.filter { it.deletedAtMs < TRASH_CLOCK_FLOOR || it.deletedAtMs > now + RESTAMP_AHEAD_MS }
        if (wrong.isEmpty()) return
        var next = items
        val nextRecords = records.toMutableMap()
        for (item in wrong) {
            val id = newId(now)
            if (!renameFile(File(root, item.id), File(root, id))) {
                log.w(TAG, "Trash entry ${item.id} could not be given the current time")
                continue
            }
            val record = item.record?.copy(trashId = id)
            nextRecords.remove(item.id)
            if (record != null) nextRecords[id] = record
            next = next - item.id + (id to item.moved(id, now, record))
            log.i(TAG, "Trash entry ${item.id} was stamped by a clock not yet set; it is now $id")
        }
        items = next
        saveRecords(nextRecords)
    }

    /**
     * Under the lock: reads the tree again. A record without its folder is dropped, a folder without a record is listed
     * without state. While the photo folder is unavailable nothing changes: an empty listing says nothing then.
     */
    private fun buildLocked() {
        if (!available()) return
        val listed = if (root.exists()) listChildren(root) else emptyArray()
        if (listed == null) {
            log.w(TAG, "The trash folder cannot be listed")
            return
        }
        val found = HashMap<String, TrashItem>()
        if (!isLink(root)) {
            for (folder in listed) {
                if (!PATTERN.matches(folder.name) || isLink(folder) || !folder.isDirectory) continue
                found[folder.name] = read(folder)
            }
        }
        items = found
        built = true
        val kept = records.filterKeys { it in found }
        if (kept.size != records.size) saveRecords(kept)
    }

    /** One entry from its folder: its image files, its bytes and its record. */
    private fun read(folder: File): TrashItem {
        val images = ArrayList<File>()
        var bytes = 0L
        walk(folder) { file ->
            bytes += file.length()
            // As the scan sees it: no hidden file and no file in a hidden folder, which a restore would put out of its sight.
            val hidden = file.relativeTo(folder).invariantSeparatorsPath.split('/').any { it.startsWith('.') }
            if (!hidden && ImageFormats.hasImageExtension(file.name)) images += file
        }
        val image = images.singleOrNull()
        val path = image?.relativeTo(folder)?.invariantSeparatorsPath
        return TrashItem(folder.name, deletedAtOf(folder.name), path, image, bytes, image == null, records[folder.name])
    }

    /** Creates `.trash` with its `.nomedia`, so gallery apps skip it; `false` if it is not a real folder. */
    private fun prepareRoot(): Boolean {
        if (root.exists() && (isLink(root) || !root.isDirectory)) {
            log.w(TAG, "The trash folder is not a folder of its own; nothing is moved there")
            return false
        }
        if (!root.isDirectory && !root.mkdirs()) return false
        val noMedia = File(root, NO_MEDIA)
        if (!noMedia.exists()) {
            try {
                noMedia.createNewFile()
            } catch (e: IOException) {
                // Only gallery apps care; the trash works without it.
            }
        }
        return true
    }

    private fun putRecord(record: TrashRecord): Boolean {
        saveRecords(records + (record.trashId to record))
        return true
    }

    private fun dropRecords(ids: Collection<String>) {
        if (ids.none { it in records }) return
        saveRecords(records - ids.toSet())
    }

    private fun saveRecords(next: Map<String, TrashRecord>) {
        records = next
        store.save(StoredTrash(next.values.sortedBy { it.trashId }))
    }

    private fun newId(now: Long): String {
        while (true) {
            val id = String.format(Locale.ROOT, "%013d-%08x", now.coerceIn(0, MAX_STAMP), random.nextInt())
            if (id !in items && !File(root, id).exists()) return id
        }
    }

    private fun TrashItem.withRecord(record: TrashRecord) = TrashItem(id, deletedAtMs, path, file, bytes, damaged, record)

    private fun TrashItem.moved(
        newId: String,
        now: Long,
        record: TrashRecord?,
    ): TrashItem {
        val folder = File(root, newId)
        return TrashItem(newId, now, path, path?.let { File(folder, it) }, bytes, damaged, record)
    }

    companion object {
        /** The trash's folder in the photo folder; the scan skips it as a dot folder. */
        const val FOLDER = ".trash"
        private const val NO_MEDIA = ".nomedia"
        private const val TAG = "trash"

        /** The form of a trash ID: the deletion time in milliseconds and 8 hex digits. */
        val PATTERN = Regex("[0-9]{13}-[0-9a-f]{8}")

        /** How long an entry stays (spec "Retention of the trash"). */
        const val RETENTION_MS = 7 * 24 * 60 * 60 * 1000L

        /** Under storage pressure only entries deleted longer ago than this go (spec "Trash under storage pressure"). */
        const val PRESSURE_AGE_MS = 24 * 60 * 60 * 1000L

        /**
         * 2026-01-01T00:00:00Z: a clock before it is not yet set (design decision 8), as on frames without a
         * battery-backed clock that start at a date years in the past.
         */
        const val TRASH_CLOCK_FLOOR = 1_767_225_600_000L

        /** An entry stamped further ahead than this of a plausible clock is re-stamped. */
        private const val RESTAMP_AHEAD_MS = 60 * 60 * 1000L
        private const val MAX_STAMP = 9_999_999_999_999L

        private fun deletedAtOf(id: String) = id.substringBefore('-').toLong()

        private fun megabytes(bytes: Long) = (bytes + 999_999) / 1_000_000

        /** The children of [dir], `null` if it cannot be listed. */
        private fun listChildren(dir: File): Array<File>? = dir.listFiles()

        /**
         * Whether [file] is a symbolic link, without `java.nio.file` (API 21): its canonical path differs from the path
         * built from its parent's canonical path. A file whose path cannot be resolved counts as one, so it is skipped.
         */
        internal fun isLink(file: File): Boolean {
            val parent = file.absoluteFile.parentFile ?: return false
            return try {
                val built = File(parent.canonicalFile, file.name)
                built.canonicalPath != built.absolutePath
            } catch (e: IOException) {
                true
            }
        }

        /** Whether [file] lies below [folder] by its canonical path. */
        private fun inside(
            file: File,
            folder: File,
        ): Boolean =
            try {
                file.canonicalPath.startsWith(folder.canonicalPath + File.separator)
            } catch (e: IOException) {
                false
            }

        /** Calls [visit] for every regular file below [dir], depth first; symbolic links are neither visited nor followed. */
        internal fun walk(
            dir: File,
            visit: (File) -> Unit,
        ) {
            for (child in listChildren(dir).orEmpty()) {
                if (isLink(child)) continue
                if (child.isDirectory) {
                    walk(child, visit)
                } else if (child.isFile) {
                    visit(child)
                }
            }
        }

        /**
         * Deletes [dir] and everything below it, depth first. A symbolic link is deleted itself, never what it points to,
         * which `deleteRecursively` would follow for a linked folder.
         */
        internal fun deleteTree(dir: File) {
            if (!dir.exists() && !isLink(dir)) return
            if (isLink(dir)) {
                dir.delete()
                return
            }
            for (child in listChildren(dir).orEmpty()) {
                if (isLink(child)) {
                    child.delete()
                } else if (child.isDirectory) {
                    deleteTree(child)
                } else {
                    child.delete()
                }
            }
            dir.delete()
        }
    }
}
