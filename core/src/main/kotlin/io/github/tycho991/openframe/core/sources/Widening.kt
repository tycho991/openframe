package io.github.tycho991.openframe.core.sources

/**
 * The widening rule of an account source (spec `remote-sources`, "Widening the folder of an account"; design decision
 * 13 of `paired-device-guards`): which fields of a change make a source that needs a folder read more than before.
 * Folders are compared by path segment and with exact case, as Nextcloud paths are case-sensitive; `/` is above
 * everything.
 */
object Widening {
    const val FOLDER = "folder"
    const val SUBFOLDERS = "includeSubfolders"

    /**
     * The fields of the change from [prev] to [next] that widen what the source reads: [FOLDER] for the first folder, a
     * folder that is neither the old one nor below it, or a folder below the old one while subfolders were off;
     * [SUBFOLDERS] for subfolders switched on. Empty for a change that does not widen. Only for kinds with
     * [SourceKind.needsFolder]: a share link's token bounds what it can read.
     */
    fun fields(
        prev: StoredSource,
        next: StoredSource,
    ): Set<String> {
        val fields = LinkedHashSet<String>()
        val before = prev.folder
        val after = next.folder
        if (after.isNotEmpty() && after != before) {
            val wider =
                when {
                    before.isEmpty() -> true
                    !below(after, before) -> true
                    else -> !prev.includeSubfolders
                }
            if (wider) fields += FOLDER
        }
        if (!prev.includeSubfolders && next.includeSubfolders) fields += SUBFOLDERS
        return fields
    }

    fun widens(
        prev: StoredSource,
        next: StoredSource,
    ): Boolean = fields(prev, next).isNotEmpty()

    /** Whether [folder] lies strictly below [parent], by segment and with exact case. */
    private fun below(
        folder: String,
        parent: String,
    ): Boolean {
        val inner = segments(folder)
        val outer = segments(parent)
        return inner.size > outer.size && inner.subList(0, outer.size) == outer
    }

    private fun segments(folder: String) = folder.split('/').filter { it.isNotEmpty() }
}
