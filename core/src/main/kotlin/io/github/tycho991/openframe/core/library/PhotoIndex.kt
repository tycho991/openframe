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
) {
    /** The first folder below the photo folder, `null` for photos directly in it. */
    val album: String? get() = path.substringBefore('/', "").ifEmpty { null }

    val name: String get() = path.substringAfterLast('/')

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

@Serializable
data class StoredIndex(
    val photos: List<PhotoEntry> = emptyList(),
    val hiddenAlbums: Set<String> = emptySet(),
    val skipped: List<SkippedFile> = emptyList(),
    val libraryVersion: Long = 0,
)

/** Immutable state of the library. */
class Library(
    val photos: Map<String, PhotoEntry>,
    val hiddenAlbums: Set<String>,
    val skipped: List<SkippedFile>,
    /** Changes with every change to the index, also across restarts. */
    val version: Long,
) {
    fun isVisible(photo: PhotoEntry) = !photo.hidden && photo.album !in hiddenAlbums

    /** All photos by capture date ascending, by path on a tie. Computed once per state. */
    val byDate: List<PhotoEntry> by lazy { photos.values.sortedWith(DATE_ORDER) }

    /** All photos by path ascending. */
    val byName: List<PhotoEntry> by lazy { photos.values.sortedWith(NAME_ORDER) }

    /** The photos visible to the slideshow by capture date. */
    val visible: List<PhotoEntry> by lazy { byDate.filter(::isVisible) }

    companion object {
        val EMPTY = Library(emptyMap(), emptySet(), emptyList(), 0)
        val DATE_ORDER: Comparator<PhotoEntry> = compareBy<PhotoEntry> { it.takenAtMs }.thenBy { it.path }.thenBy { it.id }
        val NAME_ORDER: Comparator<PhotoEntry> = compareBy<PhotoEntry> { it.path.lowercase() }.thenBy { it.path }.thenBy { it.id }
    }
}

/** Changes to a state, collected and then applied as a new state. */
class LibraryEdit internal constructor(
    from: Library,
) {
    internal val photos = from.photos.toMutableMap()
    internal val hiddenAlbums = from.hiddenAlbums.toMutableSet()
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

    fun setAlbumHidden(
        album: String,
        hidden: Boolean,
    ) {
        if (hidden) hiddenAlbums += album else hiddenAlbums -= album
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
                stored.hiddenAlbums,
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
        val changed = edit.photos != current.photos || edit.hiddenAlbums != current.hiddenAlbums || skipped != current.skipped
        if (changed) {
            val next = Library(edit.photos.toMap(), edit.hiddenAlbums.toSet(), skipped, current.version + 1)
            _library.value = next
            store.save(StoredIndex(next.photos.values.sortedBy { it.path }, next.hiddenAlbums, next.skipped, next.version))
        }
        return result
    }

    suspend fun flush() = store.flush()
}
