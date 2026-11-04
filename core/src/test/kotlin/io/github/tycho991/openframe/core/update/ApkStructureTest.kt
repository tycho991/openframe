package io.github.tycho991.openframe.core.update

import io.github.tycho991.openframe.core.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `app-update`: Verification before offering › Data before the archive (design decision 5). */
class ApkStructureTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `An APK signed with v1 and v2 passes`() {
        assertNull(ApkStructure.problem(Fixtures.file("update-0.4.0.apk")))
    }

    @Test
    fun `DEX data in front of a valid archive is refused`() {
        assertEquals("data before the first entry of the archive", ApkStructure.problem(Fixtures.file("update-janus.apk")))
    }

    @Test
    fun `Data after the end of the archive is refused`() {
        assertEquals("data after the end of the archive", ApkStructure.problem(Fixtures.file("update-appended.apk")))
    }

    @Test
    fun `An entry that does not start at offset 0 is refused even behind a local header`() {
        // A local header of its own at offset 0, then the archive with its offsets moved: the first entry is not at 0.
        val apk = Fixtures.file("update-janus.apk").readBytes()
        val dex = 112
        val forged = File(dir, "forged.apk")
        forged.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(dex - 4) + apk.copyOfRange(dex, apk.size))
        assertEquals("data before the first entry of the archive", ApkStructure.problem(forged))
    }

    @Test
    fun `Not an archive at all`() {
        val text = File(dir, "text.apk").apply { writeText("not an archive, but long enough to be looked at") }
        assertEquals("data before the first entry of the archive", ApkStructure.problem(text))
        assertEquals("too short for an archive", ApkStructure.problem(File(dir, "short.apk").apply { writeBytes(ByteArray(10)) }))
        assertEquals("not readable (FileNotFoundException)", ApkStructure.problem(File(dir, "missing.apk")))
    }
}
