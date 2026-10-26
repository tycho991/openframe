package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.platform.SkipReason
import io.github.tycho991.openframe.core.store.JsonStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** A photo in the index. Paths are relative to the source's folder, separated by `/`. */
@Serializable
data class PhotoEntry(
    val id: String,
    val source: String,
    val path: String,
    val width: Int,
    val height: Int,
    /** Capture date from EXIF, falling back to the file date. */
    val takenAtMs: Long,
    val fileSize: Long,
    val modifiedMs: Long,
    /** Increases with every change of the file so that caches become invalid. */
    val version: Int,
    val hidden: Boolean = false,
    /** The ETag of the file on the server for photos of a remote source, `null` for local photos. */
    val etag: String? = null,
    /** The photo's own section, `null` if the fill mode applies. Stored only when set. */
    val crop: Crop? = null,
) {
    /** The folder path below the source's folder, `null` for photos directly in it. */
    val album: String? get() = AlbumPaths.parent(path)

    val name: String get() = path.substringAfterLast('/')

    /**
     * This new version of the file with the photo's own state taken from [current], the entry in the index when the
     * new version is applied: the hidden state, and the section if the aspect ratio stayed. Taken there and not from
     * the entry before the probe or download, so a change saved meanwhile (`PATCH /photos`) is not lost.
     */
    fun withStateOf(current: PhotoEntry?): PhotoEntry =
        if (current == null) this else copy(hidden = current.hidden, crop = Crop.carriedOver(current, width, height))

    companion object {
        /** Stable ID from source and relative path. */
        fun idFor(
            source: String,
            path: String,
        ): String {
            val sha1 = MessageDigest.getInstance("SHA-1").digest(path.toByteArray(Charsets.UTF_8))
            return "$source-" + sha1.joinToString("") { "%02x".format(it) }
        }
    }
}

/** A file with an image extension that could not be accepted. */
@Serializable
data class SkippedFile(
    val source: String,
    val path: String,
    val reason: SkipReason,
    val fileSize: Long,
    val modifiedMs: Long,
)

/** Album paths: folder paths below a source's folder, levels separated by `/`. */
object AlbumPaths {
    /** The path without its last level, `null` at the top level. */
    fun parent(path: String): String? = path.substringBeforeLast('/', "").ifEmpty { null }

    /** `a/b/c` → `a`, `a/b`, `a/b/c`. */
    fun withAncestors(album: String): List<String> = album.indices.filter { album[it] == '/' }.map { album.substring(0, it) } + album

    /** Whether [album] is [branch] or one of its sub-albums. Called per photo, so it builds no strings. */
    fun inBranch(
        album: String?,
        branch: String,
    ): Boolean =
        album != null &&
            album.startsWith(branch) &&
            (album.length == branch.length || album[branch.length] == '/')

    /** The album's own setting from [states], otherwise its parent's; a top-level album is visible. */
    fun isHidden(
        states: Map<String, Boolean>,
        album: String,
    ): Boolean {
        var path: String? = album
        while (path != null) {
            states[path]?.let { return it }
            path = parent(path)
        }
        return false
    }

    /**
     * [paths] in tree order: level by level, each case-insensitive and then exact, so a sub-album
     * comes right after its parent, before a sibling such as `a b` after `a` or `a` after `A`.
     * Each path is split once, since a library can have thousands of albums.
     */
    fun sorted(paths: Collection<String>): List<String> {
        class Key(
            val path: String,
        ) {
            val levels = path.split('/')
            val lower = levels.map { it.lowercase() }
        }
        return paths
            .map(::Key)
            .sortedWith { a, b ->
                for (i in 0 until minOf(a.levels.size, b.levels.size)) {
                    val c = a.lower[i].compareTo(b.lower[i]).takeIf { it != 0 } ?: a.levels[i].compareTo(b.levels[i])
                    if (c != 0) return@sortedWith c
                }
                a.levels.size - b.levels.size
            }.map { it.path }
    }

    /** [states] without the entries that equal what the album would inherit anyway. */
    fun normalized(states: Map<String, Boolean>): Map<String, Boolean> {
        val kept = sortedMapOf<String, Boolean>()
        // Parents come before their children in tree order, so the inherited state is final when a child is looked at.
        for (path in sorted(states.keys)) {
            val hidden = states.getValue(path)
            val inherited = parent(path)?.let { isHidden(kept, it) } ?: false
            if (hidden != inherited) kept[path] = hidden
        }
        return kept
    }
}

@Serializable
data class StoredIndex(
    val photos: List<PhotoEntry> = emptyList(),
    /** The paths stored as hidden in [albumStates], kept so that an older build still hides them. */
    val hiddenAlbums: Set<String> = emptySet(),
    /** Album path → hidden, only where it differs from what the album inherits. `null` in an index of the previous format. */
    val albumStates: Map<String, Boolean>? = null,
    val skipped: List<SkippedFile> = emptyList(),
    val libraryVersion: Long = 0,
)

/** An album with the photos of its whole branch. */
class Album(
    val name: String,
    val count: Int,
    /** The newest photo of the branch, the cover image. */
    val newest: PhotoEntry,
    /** Effective state, by its own setting or inherited. */
    val hidden: Boolean,
    /** A sub-album with a setting of its own, which differs from its parent's state by the invariant of [LibraryEdit.setAlbumHidden]. */
    val overridesParent: Boolean,
)

/** Immutable state of the library. */
class Library(
    val photos: Map<String, PhotoEntry>,
    /** Album path → hidden, only where it differs from what the album inherits. */
    val albumStates: Map<String, Boolean>,
    val skipped: List<SkippedFile>,
    /** Changes with every change to the index, also across restarts. */
    val version: Long,
) {
    fun isVisible(photo: PhotoEntry): Boolean {
        val album = photo.album ?: return !photo.hidden
        return !photo.hidden && !(albums[album]?.hidden ?: AlbumPaths.isHidden(albumStates, album))
    }

    /**
     * Every album that holds a photo in its branch, in the tree order of [AlbumPaths.sorted], with count, cover and
     * effective state. One pass over the photos per state, so [isVisible] is a map lookup.
     */
    val albums: Map<String, Album> by lazy {
        val counts = HashMap<String, Int>()
        val newest = HashMap<String, PhotoEntry>()
        for (photo in photos.values) {
            val album = photo.album ?: continue
            for (path in AlbumPaths.withAncestors(album)) {
                counts[path] = (counts[path] ?: 0) + 1
                val current = newest[path]
                if (current == null || DATE_ORDER.compare(photo, current) > 0) newest[path] = photo
            }
        }
        AlbumPaths.sorted(counts.keys).associateWith { path ->
            Album(
                name = path,
                count = counts.getValue(path),
                newest = newest.getValue(path),
                hidden = AlbumPaths.isHidden(albumStates, path),
                overridesParent = path in albumStates && AlbumPaths.parent(path) != null,
            )
        }
    }

    /** All photos by capture date ascending, by path on a tie. Computed once per state. */
    val byDate: List<PhotoEntry> by lazy { photos.values.sortedWith(DATE_ORDER) }

    /** All photos by path ascending. */
    val byName: List<PhotoEntry> by lazy { photos.values.sortedWith(NAME_ORDER) }

    /** The photos visible to the slideshow by capture date. */
    val visible: List<PhotoEntry> by lazy { byDate.filter(::isVisible) }

    companion object {
        val EMPTY = Library(emptyMap(), emptyMap(), emptyList(), 0)
        val DATE_ORDER: Comparator<PhotoEntry> = compareBy<PhotoEntry> { it.takenAtMs }.thenBy { it.path }.thenBy { it.id }
        val NAME_ORDER: Comparator<PhotoEntry> = compareBy<PhotoEntry> { it.path.lowercase() }.thenBy { it.path }.thenBy { it.id }
    }
}

/** Changes to a state, collected and then applied as a new state. */
class LibraryEdit internal constructor(
    from: Library,
) {
    internal val photos = from.photos.toMutableMap()
    internal val albumStates = from.albumStates.toMutableMap()
    internal val skipped = from.skipped.associateBy { it.source to it.path }.toMutableMap()

    fun get(id: String): PhotoEntry? = photos[id]

    fun put(entry: PhotoEntry) {
        photos[entry.id] = entry
        skipped.remove(entry.source to entry.path)
    }

    fun remove(id: String): PhotoEntry? = photos.remove(id)

    fun skip(file: SkippedFile) {
        photos.remove(PhotoEntry.idFor(file.source, file.path))
        skipped[file.source to file.path] = file
    }

    fun unskip(
        source: String,
        path: String,
    ) {
        skipped.remove(source to path)
    }

    fun skipped(source: String): List<SkippedFile> = skipped.values.filter { it.source == source }

    fun entries(source: String): List<PhotoEntry> = photos.values.filter { it.source == source }

    /**
     * Makes [hidden] the state of the album's whole branch: the settings of all its sub-albums are
     * removed, and the album keeps a setting of its own only if it differs from what it inherits.
     */
    fun setAlbumHidden(
        album: String,
        hidden: Boolean,
    ) {
        albumStates.keys.removeAll { it != album && AlbumPaths.inBranch(it, album) }
        val inherited = AlbumPaths.parent(album)?.let { AlbumPaths.isHidden(albumStates, it) } ?: false
        if (hidden == inherited) albumStates -= album else albumStates[album] = hidden
    }
}

/**
 * The index in memory. Every change yields a new [Library] state with a
 * new version, becomes visible immediately as [library] and is stored in batches.
 */
class PhotoIndexStore(
    private val store: JsonStore<StoredIndex>,
) {
    private val _library = MutableStateFlow(Library.EMPTY)

    val library: StateFlow<Library> = _library.asStateFlow()

    fun load() {
        val stored = store.load() ?: StoredIndex()
        _library.value =
            Library(
                stored.photos.associateBy { it.id },
                // The previous format knew only hidden albums, which their sub-albums inherit. An older build
                // can have added a parent to paths this build wrote there, so redundant entries are dropped.
                AlbumPaths.normalized(stored.albumStates ?: stored.hiddenAlbums.associateWith { true }),
                stored.skipped,
                stored.libraryVersion,
            )
    }

    /** Runs [block] and applies the result if something has changed. */
    @Synchronized
    fun <R> edit(block: (LibraryEdit) -> R): R {
        val current = _library.value
        val edit = LibraryEdit(current)
        val result = block(edit)
        val skipped = edit.skipped.values.sortedWith(compareBy({ it.source }, { it.path }))
        val changed = edit.photos != current.photos || edit.albumStates != current.albumStates || skipped != current.skipped
        if (changed) {
            val next = Library(edit.photos.toMap(), edit.albumStates.toSortedMap(), skipped, current.version + 1)
            _library.value = next
            store.save(
                StoredIndex(
                    photos = next.photos.values.sortedBy { it.path },
                    hiddenAlbums = next.albumStates.filterValues { it }.keys,
                    albumStates = next.albumStates,
                    skipped = next.skipped,
                    libraryVersion = next.version,
                ),
            )
        }
        return result
    }

    suspend fun flush() = store.flush()
}
