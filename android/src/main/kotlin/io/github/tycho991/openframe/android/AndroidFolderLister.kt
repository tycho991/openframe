package io.github.tycho991.openframe.android

import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import io.github.tycho991.openframe.core.platform.FileFacts
import io.github.tycho991.openframe.core.platform.FolderEntry
import io.github.tycho991.openframe.core.platform.FolderLister
import io.github.tycho991.openframe.core.platform.FolderListing
import io.github.tycho991.openframe.core.platform.walkFolder
import java.io.File

/**
 * Lists the photo folder with one [Os.stat] per entry: type, size and time in one query, where `java.io` needs up to
 * four, each a round trip through the FUSE daemon of the shared storage (design decision 1 of `scan-efficiency`). The
 * times follow what `File.lastModified()` gives on the same API level; `FrameCore` checks that once per process and
 * falls back to `java.io` if they differ (decision 2).
 */
class AndroidFolderLister : FolderLister {
    override fun list(root: File): FolderListing? =
        walkFolder(root) { dir, name, image ->
            val file = File(dir, name)
            try {
                val stat = Os.stat(file.path)
                entryOf(file, stat.st_mode, stat.st_size, modifiedMs(stat), image)
            } catch (e: ErrnoException) {
                entryOfError(e.errno, OsConstants.ENOENT)
            }
        }

    internal companion object {
        // POSIX file types; the same values on every Linux, so the mapping can be tested on the JVM.
        private const val S_IFMT = 0xF000
        private const val S_IFDIR = 0x4000
        private const val S_IFREG = 0x8000

        /** The entry for a [mode] from `stat`: a folder, an image file with its facts, or anything else. */
        fun entryOf(
            file: File,
            mode: Int,
            size: Long,
            modifiedMs: Long,
            image: Boolean,
        ): FolderEntry =
            when (mode and S_IFMT) {
                S_IFDIR -> FolderEntry.Folder
                S_IFREG -> if (image) FolderEntry.ImageFile(FileFacts(file, size, modifiedMs)) else FolderEntry.Other
                else -> FolderEntry.Other
            }

        /** A failed `stat`: an entry that is gone is simply left out, any other error counts as unreadable. */
        fun entryOfError(
            errno: Int,
            enoent: Int,
        ): FolderEntry = if (errno == enoent) FolderEntry.Other else FolderEntry.Unreadable

        /**
         * The modification time in milliseconds: whole seconds below API 27, where `StructStat` has only `st_mtime`, and
         * from `st_mtim` from API 27 on.
         */
        fun modifiedMs(
            sdk: Int,
            seconds: Long,
            timespecSeconds: Long,
            timespecNanos: Long,
        ): Long = if (sdk >= 27) timespecSeconds * 1000 + timespecNanos / 1_000_000 else seconds * 1000

        private fun modifiedMs(stat: StructStat): Long =
            if (Build.VERSION.SDK_INT >= 27) {
                modifiedMs(Build.VERSION.SDK_INT, stat.st_mtime, stat.st_mtim.tv_sec, stat.st_mtim.tv_nsec)
            } else {
                modifiedMs(Build.VERSION.SDK_INT, stat.st_mtime, 0, 0)
            }
    }
}
