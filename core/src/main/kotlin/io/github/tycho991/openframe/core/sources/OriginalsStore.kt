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
    private val reserveBytes: Long = RESERVE_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
    /** Lists the folder; every listing goes through here, so that a test can count them. */
    private val listFolder: (File) -> Array<File>? = { it.listFiles() },
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

    /** Free storage minus the reserve: what a download may take. */
    fun roomOnDisk(): Long = freeBytes(dir) - reserveBytes

    /** Whether [needed] more bytes leave at least the reserve of free storage. */
    fun hasRoomOnDisk(needed: Long): Boolean = freeBytes(dir) - needed >= reserveBytes

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
    ): Int {
        if (totalBytes() <= budgetBytes) return 0
        // Each file is asked for its time and size once.
        val all =
            files()
                .filter { !it.name.endsWith(PART) }
                .map { Triple(it, it.lastModified(), it.length()) }
        var sum = all.sumOf { it.third }
        var removed = 0
        for ((file, _, length) in all.sortedBy { it.second }) {
            if (sum <= budgetBytes) break
            if (file == keep || file.name in pinned) continue
            if (file.delete()) {
                sum -= length
                removed++
            }
        }
        total = sum
        return removed
    }

    /** Removes the original of the photo with [id], whatever its extension. */
    @Synchronized
    fun remove(id: String) {
        files()
            .filter { it.name.startsWith("$id.") }
            .forEach { file ->
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

        /** A download never leaves less than this free (the reserve that uploads keep as well). */
        const val RESERVE_BYTES = 100L * 1024 * 1024
    }
}
