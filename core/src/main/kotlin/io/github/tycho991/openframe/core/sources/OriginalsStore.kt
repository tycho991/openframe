package io.github.tycho991.openframe.core.sources

import io.github.tycho991.openframe.core.library.PhotoEntry
import java.io.File

/**
 * The originals of the photos of one remote source on the frame: a cache, not the only copy. A file is `<photo id>.<extension>`;
 * it is written as `.part` first and renamed when complete, so a half file is never taken for an original. The
 * modification time is the time of the last use, and when the budget is exceeded the files with the oldest time go.
 *
 * It keeps a running total of its bytes, read from the folder once when first needed and again at the start of every
 * sync ([refresh]), because Android may trim the cache folder; the budget check costs nothing more. Thread-safe: the
 * sync and the download thread of the renderings use it at once.
 */
class OriginalsStore(
    private val dir: File,
    private val freeBytes: (File) -> Long = { it.usableSpace },
    /** The storage reserve a download leaves free ([io.github.tycho991.openframe.core.library.StorageReserve]). */
    private val reserveBytes: Long = ON_DEMAND_FLOOR_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
    /** Lists the folder; every listing goes through here, so that a test can count them. */
    private val listFolder: (File) -> Array<File>? = { it.listFiles() },
    /** Frees storage outside the store for the given free bytes in a folder: old trash entries (`paired-device-guards`). */
    private val makeRoom: (File, Long) -> Boolean = { _, _ -> false },
) {
    init {
        dir.mkdirs()
        // Leftovers of a run that was cut off.
        files()
            .filter { it.name.endsWith(PART) }
            .forEach { it.delete() }
    }

    /** The bytes of the originals, `null` until it is first needed. */
    private var total: Long? = null

    /** Names of originals that a rendering waits for; a trim leaves them, see [pin]. */
    private val pinned = HashMap<String, Int>()

    private fun files(): List<File> = listFolder(dir).orEmpty().toList()

    /** Where the original of [entry] is, or would be. */
    fun fileOf(entry: PhotoEntry): File = File(dir, entry.id + "." + extensionOf(entry.path))

    /** Where the original is written first. */
    fun partOf(entry: PhotoEntry): File = File(dir, entry.id + "." + extensionOf(entry.path) + PART)

    fun has(entry: PhotoEntry) = fileOf(entry).isFile

    /** Marks the original as used just now. */
    fun touch(entry: PhotoEntry) {
        fileOf(entry).setLastModified(now())
    }

    /** Moves a finished download into place and marks it as used. */
    @Synchronized
    fun commit(entry: PhotoEntry) {
        val part = partOf(entry)
        val target = fileOf(entry)
        val replaced = if (target.isFile) target.length() else 0L
        if (target.exists()) target.delete()
        check(part.renameTo(target)) { "Renaming ${part.name} failed" }
        target.setLastModified(now())
        total?.let { total = it - replaced + target.length() }
    }

    /** Reads the total from the folder again, at the start of a sync. */
    @Synchronized
    fun refresh() {
        total = files().filter { it.isFile && !it.name.endsWith(PART) }.sumOf { it.length() }
    }

    @Synchronized
    fun totalBytes(): Long =
        total ?: run {
            refresh()
            total!!
        }

    /**
     * Free storage minus what a download leaves free: the reserve, or for a rendering somebody awaits ([onDemand]) the
     * floor [ON_DEMAND_FLOOR_BYTES] (spec `remote-sources`, "Storage of the originals").
     */
    fun roomOnDisk(onDemand: Boolean = false): Long = freeBytes(dir) - floor(onDemand)

    /** Whether [needed] more bytes leave at least the reserve, or for [onDemand] the floor, of free storage. */
    fun hasRoomOnDisk(
        needed: Long,
        onDemand: Boolean = false,
    ): Boolean = freeBytes(dir) - needed >= floor(onDemand)

    /**
     * [hasRoomOnDisk] for the original of a rendering, after the render cache trimmed itself: if it is short, old trash
     * entries make room when they free enough (design decision 9 of `paired-device-guards`). Never under this store's
     * lock.
     */
    fun ensureRoomOnDisk(
        needed: Long,
        onDemand: Boolean = false,
    ): Boolean = hasRoomOnDisk(needed, onDemand) || (makeRoom(dir, needed + floor(onDemand)) && hasRoomOnDisk(needed, onDemand))

    private fun floor(onDemand: Boolean) = if (onDemand) minOf(ON_DEMAND_FLOOR_BYTES, reserveBytes) else reserveBytes

    /** Whether [needed] more bytes stay within [budgetBytes]. */
    fun fitsBudget(
        needed: Long,
        budgetBytes: Long,
    ): Boolean = totalBytes() + needed <= budgetBytes

    /** Keeps the original of [entry] through every [trim] until as many [unpin]s, while renderings wait for it. */
    @Synchronized
    fun pin(entry: PhotoEntry) {
        val name = fileOf(entry).name
        pinned[name] = (pinned[name] ?: 0) + 1
    }

    @Synchronized
    fun unpin(entry: PhotoEntry) {
        val name = fileOf(entry).name
        val left = (pinned[name] ?: return) - 1
        if (left <= 0) pinned.remove(name) else pinned[name] = left
    }

    /**
     * Removes the files with the oldest use until the total is within [budgetBytes], never [keep] (the original that was
     * just fetched for a rendering) and no pinned one. Returns the number removed.
     */
    @Synchronized
    fun trim(
        budgetBytes: Long,
        keep: File? = null,
        /** Originals of photos about to leave the library, worked out once with [leaving]: not counted, not trimmed. */
        uncounted: Leaving = Leaving.NONE,
    ): Int {
        val leaving = uncounted.names
        if (totalBytes() - uncounted.bytes <= budgetBytes) return 0
        // Each file is asked for its time and size once.
        val all =
            files()
                .filter { !it.name.endsWith(PART) }
                .map { Triple(it, it.lastModified(), it.length()) }
        var sum = all.filter { it.first.name !in leaving }.sumOf { it.third }
        var removed = 0
        for ((file, _, length) in all.sortedBy { it.second }) {
            if (sum <= budgetBytes) break
            if (file == keep || file.name in pinned || file.name in leaving) continue
            if (file.delete()) {
                sum -= length
                removed++
            }
        }
        total = sum + all.filter { it.first.name in leaving }.sumOf { it.third }
        return removed
    }

    /** The file names of originals about to leave the library and their bytes, for [trim]. */
    class Leaving internal constructor(
        val names: Set<String>,
        val bytes: Long,
    ) {
        companion object {
            val NONE = Leaving(emptySet(), 0)
        }
    }

    /** The originals of [entries] that are on the frame, each asked for its size once. */
    fun leaving(entries: Collection<PhotoEntry>): Leaving {
        if (entries.isEmpty()) return Leaving.NONE
        val files = entries.map { fileOf(it) }
        return Leaving(files.mapTo(HashSet()) { it.name }, files.sumOf { it.length() })
    }

    /** Removes the original of the photo with [id], whatever its extension. */
    fun remove(id: String) = removeAll(listOf(id))

    /**
     * Removes the originals of the photos with [ids], whatever their extension, with one listing of the folder: a sync
     * that removes thousands of vanished photos would otherwise list it once per photo.
     */
    @Synchronized
    fun removeAll(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val set = ids.toHashSet()
        // A file is an original of a photo if its name starts with the ID and a '.'. Every prefix that ends before a '.' is
        // looked up, so an ID that contains '.' itself is matched as well.
        files()
            .filter { file ->
                val name = file.name
                var dot = name.indexOf('.')
                while (dot >= 0 && name.substring(0, dot) !in set) dot = name.indexOf('.', dot + 1)
                dot >= 0
            }.forEach { file ->
                val length = if (file.name.endsWith(PART)) 0L else file.length()
                if (file.delete()) total?.let { total = it - length }
            }
    }

    @Synchronized
    fun clear() {
        files().forEach { it.delete() }
        total = 0L
    }

    /** Removes the folder with everything in it, when the source is removed. */
    fun delete() {
        clear()
        dir.delete()
    }

    private fun extensionOf(path: String) =
        path
            .substringAfterLast('.', "bin")
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .ifEmpty { "bin" }

    companion object {
        const val PART = ".part"

        /**
         * A download for a rendering somebody awaits never leaves less than this free, also below the storage reserve
         * (design decision 1 of `library-performance`): the reserve of the versions before it.
         */
        const val ON_DEMAND_FLOOR_BYTES = 100L * 1024 * 1024
    }
}
