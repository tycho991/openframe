package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.library.PhotoEntry
import java.io.File

/**
 * The originals of Nextcloud photos on the frame: a cache, not the only copy. A file is `<photo id>.<extension>`;
 * it is written as `.part` first and renamed when complete, so a half file is never taken for an original. The
 * modification time is the time of the last use, and when the budget is exceeded the files with the oldest time go.
 */
class OriginalsStore(
    private val dir: File,
    private val freeBytes: (File) -> Long = { it.usableSpace },
    private val reserveBytes: Long = RESERVE_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
) {
    init {
        dir.mkdirs()
        // Leftovers of a run that was cut off.
        dir
            .listFiles()
            .orEmpty()
            .filter { it.name.endsWith(PART) }
            .forEach { it.delete() }
    }

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
    fun commit(entry: PhotoEntry) {
        val part = partOf(entry)
        val target = fileOf(entry)
        if (target.exists()) target.delete()
        check(part.renameTo(target)) { "Renaming ${part.name} failed" }
        target.setLastModified(now())
    }

    fun totalBytes(): Long =
        dir
            .listFiles()
            .orEmpty()
            .filter { it.isFile && !it.name.endsWith(PART) }
            .sumOf { it.length() }

    /** Whether [needed] more bytes leave at least the reserve of free storage. */
    fun hasRoomOnDisk(needed: Long): Boolean = freeBytes(dir) - needed >= reserveBytes

    /** Whether [needed] more bytes stay within [budgetBytes]. */
    fun fitsBudget(
        needed: Long,
        budgetBytes: Long,
    ): Boolean = totalBytes() + needed <= budgetBytes

    /**
     * Removes the files with the oldest use until the total is within [budgetBytes], never [keep] (the original that was
     * just fetched for a rendering). Returns the number removed.
     */
    fun trim(
        budgetBytes: Long,
        keep: File? = null,
    ): Int {
        var total = totalBytes()
        var removed = 0
        val byAge =
            dir
                .listFiles()
                .orEmpty()
                .filter { it.isFile && !it.name.endsWith(PART) && it != keep }
                .sortedBy { it.lastModified() }
        for (file in byAge) {
            if (total <= budgetBytes) break
            total -= file.length()
            if (file.delete()) removed++
        }
        return removed
    }

    /** Removes the original of the photo with [id], whatever its extension. */
    fun remove(id: String) {
        dir
            .listFiles()
            .orEmpty()
            .filter { it.name.startsWith("$id.") }
            .forEach { it.delete() }
    }

    fun clear() {
        dir.listFiles().orEmpty().forEach { it.delete() }
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
