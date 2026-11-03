package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.store.AtomicFile
import io.github.tycho991.openframe.core.store.StoreJson
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Which photo folder the frame uses (spec `photo-library`, "Photo folder kept"). The choice is stored in
 * `state/storage.json`, outside the [io.github.tycho991.openframe.core.store.JsonStore]s, because the app needs it
 * before the core exists. There is no silent switch to internal storage while the external storage is not mounted yet.
 */
object PhotoRoot {
    /** The state of the shared external storage. */
    enum class Storage {
        MOUNTED,

        /** There is shared storage, but it is not mounted right now, for example shortly after a boot. */
        NOT_MOUNTED,

        /** The device has no shared storage at all (removed or unmountable). */
        NONE,
    }

    /** The folder to use, and whether to store it as the frame's folder. */
    data class Choice(
        val folder: File,
        val store: Boolean,
    )

    /**
     * [external] is the app's external folder if the system reports one now, [expectedExternal] where it would be.
     * [internal] is used only by a device that never stored a folder and has no shared storage at all.
     */
    fun resolve(
        stored: String?,
        external: File?,
        expectedExternal: File,
        internal: File,
        storage: Storage,
    ): Choice =
        when {
            external != null -> Choice(external, external.absolutePath != stored)

            stored != null -> Choice(File(stored), false)

            storage == Storage.NONE -> Choice(internal, true)

            // Not mounted yet at the first start: the folder is unavailable until the storage is mounted.
            else -> Choice(expectedExternal, false)
        }

    /** The stored folder, `null` if there is none or it cannot be read. */
    fun read(file: File): String? =
        try {
            AtomicFile(file).readText()?.let { StoreJson.decodeFromString(StoredPhotoRoot.serializer(), it).photos }
        } catch (e: Exception) {
            null
        }

    /** Stores [folder]; `false` if that failed, for example on a full disk, and is tried again at the next start. */
    fun write(
        file: File,
        folder: File,
    ): Boolean =
        try {
            AtomicFile(file).writeText(StoreJson.encodeToString(StoredPhotoRoot.serializer(), StoredPhotoRoot(folder.absolutePath)))
            true
        } catch (e: Exception) {
            false
        }

    @Serializable
    private class StoredPhotoRoot(
        val photos: String,
    )
}
