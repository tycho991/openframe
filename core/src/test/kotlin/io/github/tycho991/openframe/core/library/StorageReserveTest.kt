package io.github.tycho991.openframe.core.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Spec `photo-library`: Storage reserve. */
class StorageReserveTest {
    private val mib = 1024L * 1024

    @Test
    fun `Reference frame`() {
        assertEquals(600 * mib, StorageReserve.bytes(16L * 1024 * mib))
    }

    @Test
    fun `Small volume`() {
        assertEquals(400 * mib, StorageReserve.bytes(3_000 * mib))
    }

    @Test
    fun `A volume of unknown size keeps the margin`() {
        assertEquals(100 * mib, StorageReserve.bytes(0))
        assertEquals(100 * mib, StorageReserve.bytes(-1))
    }
}
