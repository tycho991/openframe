package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.library.PhotoRoot.Choice
import io.github.tycho991.openframe.core.library.PhotoRoot.Storage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `photo-library`: Photo folder kept. */
class PhotoRootTest {
    @TempDir
    lateinit var dir: File

    private val external = File("/storage/emulated/0/Android/data/app/files/photos")
    private val card = File("/storage/1234-5678/Android/data/app/files/photos")
    private val internal = File("/data/data/app/files/photos")

    private fun resolve(
        stored: String?,
        external: File?,
        storage: Storage,
    ) = PhotoRoot.resolve(stored, external, this.external, internal, storage)

    @Test
    fun `Stored, external and none against every storage state`() {
        for (storage in Storage.entries) {
            // The system reports the external folder: it is used, and stored if it is not the stored one.
            assertEquals(Choice(external, true), resolve(null, external, storage), "$storage")
            assertEquals(Choice(external, false), resolve(external.path, external, storage), "$storage")
            assertEquals(Choice(external, true), resolve(card.path, external, storage), "$storage")
            // Not reported now: the stored folder stays, never the internal one.
            assertEquals(Choice(card, false), resolve(card.path, null, storage), "$storage")
            assertEquals(Choice(internal, false), resolve(internal.path, null, storage), "$storage")
        }
        // Nothing stored and nothing reported: internal storage only on a device without shared storage at all.
        assertEquals(Choice(internal, true), resolve(null, null, Storage.NONE))
        assertEquals(Choice(external, false), resolve(null, null, Storage.NOT_MOUNTED), "unavailable until it is mounted")
        assertEquals(Choice(external, false), resolve(null, null, Storage.MOUNTED))
    }

    @Test
    fun `The choice survives in storage json, and a damaged file reads as none`() {
        val record = File(dir, "state/storage.json")
        assertNull(PhotoRoot.read(record))
        assertTrue(PhotoRoot.write(record, external))
        assertEquals(external.absolutePath, PhotoRoot.read(record))
        record.writeText("{ damaged")
        assertNull(PhotoRoot.read(record))
    }
}
