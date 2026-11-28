package io.github.tycho991.openframe.core.platform

import io.github.tycho991.openframe.core.log.AppLog
import java.io.File

/** What a scan compares of one image file in the photo folder: its size and modification time, read with the listing. */
data class FileFacts(
    val file: File,
    val size: Long,
    /** As [File.lastModified] gives it, so that the index's times stay comparable. */
    val modifiedMs: Long,
)

/**
 * The image files below a folder by relative path ("a/b.jpg"), names starting with a dot and everything below folders
 * starting with a dot left out, and how many entries could not be read.
 */
data class FolderListing(
    val files: Map<String, FileFacts>,
    val unreadable: Int = 0,
)

/**
 * Lists the photo folder for a scan (spec `photo-library`, "Cost of an unchanged scan"): whether an entry is a file,
 * its size and its time with as few queries as the platform allows. Image files are those with an image extension
 * ([ImageFormats.hasImageExtension]). An entry that is gone by the time it is read is left out.
 */
fun interface FolderLister {
    /** The listing of [root], or `null` if [root] itself cannot be listed. */
    fun list(root: File): FolderListing?
}

/**
 * The walk both listers share: one [File.list] per folder, an explicit stack instead of recursion, and paths built as
 * `prefix + name` with `/`, the same as `File.relativeTo(root).invariantSeparatorsPath`. [visit] is called for every
 * name that does not start with a dot, with the folder's path, and returns the entry's kind and facts.
 */
inline fun walkFolder(
    root: File,
    visit: (dir: File, name: String, image: Boolean) -> FolderEntry,
): FolderListing? {
    val rootNames = root.list() ?: return null
    val files = HashMap<String, FileFacts>()
    var unreadable = 0
    val stack = ArrayDeque<Triple<File, String, Array<String>>>()
    stack.addLast(Triple(root, "", rootNames))
    while (stack.isNotEmpty()) {
        val (dir, prefix, names) = stack.removeLast()
        for (name in names) {
            if (name.startsWith('.')) continue
            when (val entry = visit(dir, name, ImageFormats.hasImageExtension(name))) {
                is FolderEntry.ImageFile -> {
                    files[prefix + name] = entry.facts
                }

                is FolderEntry.Folder -> {
                    val sub = File(dir, name)
                    val subNames = sub.list()
                    if (subNames != null) stack.addLast(Triple(sub, "$prefix$name/", subNames))
                }

                FolderEntry.Unreadable -> {
                    unreadable++
                }

                FolderEntry.Other -> {}
            }
        }
    }
    return FolderListing(files, unreadable)
}

/** One entry of the walk in [walkFolder]. */
sealed class FolderEntry {
    class ImageFile(
        val facts: FileFacts,
    ) : FolderEntry()

    object Folder : FolderEntry()

    /** Could not be read, for a reason other than being gone. */
    object Unreadable : FolderEntry()

    /** Gone, not an image file, or neither file nor folder. */
    object Other : FolderEntry()
}

/**
 * The lister with `java.io` only: [File.isFile] only for image names, otherwise [File.isDirectory], then
 * [File.length] and [File.lastModified]. The default on the JVM and the fallback of [CheckedFolderLister].
 */
object JvmFolderLister : FolderLister {
    override fun list(root: File): FolderListing? =
        walkFolder(root) { dir, name, image ->
            val file = File(dir, name)
            when {
                image && file.isFile -> {
                    val size = file.length()
                    val time = file.lastModified()
                    // Gone between the two calls: both are 0 for a missing file.
                    if (size == 0L && time == 0L &&
                        !file.exists()
                    ) {
                        FolderEntry.Other
                    } else {
                        FolderEntry.ImageFile(FileFacts(file, size, time))
                    }
                }

                file.isDirectory -> {
                    FolderEntry.Folder
                }

                else -> {
                    FolderEntry.Other
                }
            }
        }
}

/**
 * A faster lister checked once against [File.lastModified] (spec `photo-library`, "Cost of an unchanged scan" › Times
 * read another way; design decision 2 of `scan-efficiency`). At the first listing that holds files, up to [SAMPLE] of
 * them are compared with [check]. If one differs, the faster lister would make every known photo look changed, so the
 * frame logs it once and uses [fallback] for the rest of the process.
 */
class CheckedFolderLister(
    private val fast: FolderLister,
    private val fallback: FolderLister,
    private val log: AppLog,
    private val check: (File) -> Long = File::lastModified,
) : FolderLister {
    @Volatile private var chosen: FolderLister? = null

    override fun list(root: File): FolderListing? {
        chosen?.let { return it.list(root) }
        val listing = fast.list(root) ?: return null
        var compared = 0
        for (facts in listing.files.values) {
            if (compared == SAMPLE) break
            val expected = check(facts.file)
            // A file deleted since it was listed says nothing about the lister.
            if (expected == 0L && !facts.file.exists()) continue
            compared++
            if (expected != facts.modifiedMs) {
                log.w(
                    TAG,
                    "The fast folder listing gives other file times (${facts.modifiedMs} instead of $expected ms); the scan uses java.io",
                )
                chosen = fallback
                return fallback.list(root)
            }
        }
        // An empty folder decides nothing: the check waits for a listing with files.
        if (compared > 0) chosen = fast
        return listing
    }

    private companion object {
        const val TAG = "scan"
        const val SAMPLE = 20
    }
}
