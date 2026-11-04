package io.github.tycho991.openframe.core.update

import io.github.tycho991.openframe.core.platform.ApkVerdict
import io.github.tycho991.openframe.core.testing.Fixtures
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Spec `app-update`: Download of an update, Kept download checked again, Verification before offering and Rejected
 * download is not fetched again (design decision 4).
 */
class UpdateDownloadTest {
    @TempDir
    lateinit var dir: File

    private val h by lazy { UpdateHarness(dir) }

    @AfterEach
    fun stop() = h.close()

    private val downloads get() = h.fake.downloads.get()

    private fun files() =
        h.updates
            .listFiles()
            .orEmpty()
            .map { it.name }
            .sorted()

    /** Spec `app-update`: Download of an update › Update found. */
    @Test
    fun `Update found`() {
        h.fake.publish("0.4.0")
        h.start()
        h.minutes(3)
        assertEquals(UpdatePhase.READY, h.state.phase)
        assertEquals("0.4.0", h.state.readyVersion)
        assertEquals(listOf("openframe-0.4.0.apk"), files())
        assertArrayEquals(Fixtures.apk("0.4.0"), File(h.updates, "openframe-0.4.0.apk").readBytes())
        assertEquals("openframe-0.4.0.apk", h.stored().ready?.file)
    }

    @Test
    fun `A newer release replaces the ready one, and other files go`() {
        File(h.updates.apply { mkdirs() }, "left-over.apk.part").writeText("old")
        h.ready()
        h.fake.publish("0.5.0")
        h.hours(6)
        assertEquals("0.5.0", h.state.readyVersion)
        assertEquals(listOf("openframe-0.5.0.apk"), files())
    }

    /** Spec `app-update`: Download of an update › Installed in the meantime. */
    @Test
    fun `Installed in the meantime`() {
        h.ready()
        h.stop()
        h.platform.appVersion = "0.4.0"
        h.platform.appVersionCode = 400
        h.start()
        assertEquals(UpdatePhase.IDLE, h.state.phase)
        assertEquals(CheckResult.UP_TO_DATE, h.state.lastCheck?.result)
        assertEquals(emptyList<String>(), files())
    }

    @Test
    fun `Not enough free storage`() {
        val apk = Fixtures.apk("0.4.0")
        h.fake.publish("0.4.0", apk)
        h.platform.free = 2L * apk.size + 100 * 1024 * 1024 - 1
        h.start()
        h.minutes(3)
        assertEquals(UpdateErrorCode.UPDATE_STORAGE, h.state.error?.code)
        assertEquals(0, downloads)
        h.platform.free = 2L * apk.size + 100 * 1024 * 1024
        h.minutes(15)
        assertEquals(UpdatePhase.READY, h.state.phase)
    }

    // Kept download checked again

    /** Spec `app-update`: Kept download checked again › Download damaged on disk (at the start). */
    @Test
    fun `Download damaged on disk, found at the start`() {
        h.ready()
        h.stop()
        File(h.updates, "openframe-0.4.0.apk").appendText("x")
        h.start()
        assertEquals(UpdatePhase.IDLE, h.state.phase)
        assertTrue(h.logged("Kept download of 0.4.0 changed").isNotEmpty())
        h.minutes(3)
        assertEquals(UpdatePhase.READY, h.state.phase)
        assertEquals(2, downloads)
    }

    @Test
    fun `Download deleted, found at the start`() {
        h.ready()
        h.stop()
        File(h.updates, "openframe-0.4.0.apk").delete()
        h.start()
        assertEquals(UpdatePhase.IDLE, h.state.phase)
        h.minutes(3)
        assertEquals(UpdatePhase.READY, h.state.phase)
    }

    /** Spec `app-update`: Kept download checked again › Download damaged on disk (before installing). */
    @Test
    fun `Download damaged on disk, found before installing`() {
        h.ready()
        File(h.updates, "openframe-0.4.0.apk").appendText("x")
        assertEquals(InstallRequest.Accepted, h.manager.install(null))
        h.scope.testScheduler.runCurrent()
        assertTrue(h.installer.calls.none { it.startsWith("create") })
        assertFalse(h.state.phase == UpdatePhase.READY || h.state.phase == UpdatePhase.INSTALLING)
        h.minutes(2)
        assertEquals(UpdatePhase.READY, h.state.phase)
        assertEquals(2, downloads)
    }

    // Verification before offering

    /** Spec `app-update`: Verification before offering › Damaged download; Rejected download (digest failure retried). */
    @Test
    fun `Damaged download`() {
        h.fake.publish("0.4.0")
        h.fake.corrupt = true
        h.start()
        h.minutes(3)
        assertEquals(UpdateErrorCode.UPDATE_CHECKSUM, h.state.error?.code)
        assertEquals(UpdatePhase.IDLE, h.state.phase)
        assertEquals(emptyList<String>(), files())
        assertNull(h.stored().rejected)
        h.fake.corrupt = false
        h.minutes(15)
        assertEquals(2, downloads)
        assertEquals(UpdatePhase.READY, h.state.phase)
    }

    @Test
    fun `A download of another size fails the checksum`() {
        val apk = Fixtures.apk("0.4.0")
        h.fake.publish("0.4.0", apk)
        h.fake.release = h.fake.release!!.let { r -> r.copy(assets = r.assets.map { it.copy(size = it.size - 1) }) }
        h.start()
        h.minutes(3)
        assertEquals(UpdateErrorCode.UPDATE_CHECKSUM, h.state.error?.code)
        assertEquals(emptyList<String>(), files())
    }

    /** Spec `app-update`: Verification before offering › Different signature; Rejected download is not fetched again. */
    @Test
    fun `Different signature stays rejected`() {
        h.installer.verdict = { ApkVerdict.Rejected(UpdateErrorCode.UPDATE_SIGNATURE, "signed with another certificate") }
        h.fake.publish("0.4.0")
        h.start()
        h.minutes(3)
        assertEquals(UpdateErrorCode.UPDATE_SIGNATURE, h.state.error?.code)
        assertEquals(UpdatePhase.IDLE, h.state.phase)
        assertEquals(emptyList<String>(), files())
        // No backoff, and the next check finds the same APK: not downloaded again, the rejection stays.
        h.minutes(30)
        assertEquals(1, h.fake.requests.get())
        h.hours(6)
        assertEquals(2, h.fake.requests.get())
        assertEquals(1, downloads)
        assertEquals(UpdateErrorCode.UPDATE_SIGNATURE, h.state.error?.code)
        assertEquals(CheckResult.FAILED, h.state.lastCheck?.result)
        // Also after a restart; a new installed version tries it again.
        h.restart()
        h.hours(6)
        assertEquals(1, downloads)
        h.stop()
        h.platform.appVersionCode = 301
        h.start()
        h.hours(6)
        assertEquals(2, downloads)
    }

    @Test
    fun `A new APK of the same release is downloaded after a rejection`() {
        h.installer.verdict = { ApkVerdict.Rejected(UpdateErrorCode.UPDATE_SIGNATURE, "signed with another certificate") }
        h.fake.publish("0.4.0")
        h.start()
        h.minutes(3)
        h.installer.verdict = { ApkVerdict.Ok }
        h.fake.publish("0.4.0", Fixtures.apk("0.4.0 rebuilt"))
        h.hours(6)
        assertEquals(UpdatePhase.READY, h.state.phase)
        assertEquals(2, downloads)
    }

    /** Spec `app-update`: Verification before offering › Data before the archive. */
    @Test
    fun `Data before the archive`() {
        h.fake.publish("0.4.0", Fixtures.file("update-janus.apk").readBytes())
        h.start()
        h.minutes(3)
        assertEquals(UpdateErrorCode.UPDATE_INVALID_APK, h.state.error?.code)
        assertTrue(
            h.state.error!!
                .message
                .contains("data before the first entry"),
        )
        assertEquals(emptyList<String>(), files())
        assertTrue(h.installer.calls.isEmpty())
        h.hours(6)
        assertEquals(1, downloads)
    }

    @Test
    fun `Data after the archive`() {
        h.fake.publish("0.4.0", Fixtures.file("update-appended.apk").readBytes())
        h.start()
        h.minutes(3)
        assertEquals(UpdateErrorCode.UPDATE_INVALID_APK, h.state.error?.code)
    }

    /** Spec `app-update`: Verification before offering › Debug build in a release. */
    @Test
    fun `Debug build in a release`() {
        h.installer.verdict = { ApkVerdict.Rejected(UpdateErrorCode.UPDATE_INVALID_APK, "a debug build") }
        h.fake.publish("0.4.0")
        h.start()
        h.minutes(3)
        assertEquals(UpdateErrorCode.UPDATE_INVALID_APK, h.state.error?.code)
        assertEquals(UpdatePhase.IDLE, h.state.phase)
    }

    @Test
    fun `A failed download goes into the backoff`() {
        h.fake.publish("0.4.0")
        h.fake.downloadFailure = UpdateException(UpdateErrorCode.UPDATE_UNREACHABLE, "release-assets is not reachable")
        h.start()
        h.minutes(3)
        assertEquals(UpdateErrorCode.UPDATE_UNREACHABLE, h.state.error?.code)
        assertEquals(1, h.stored().failures)
        h.fake.downloadFailure = null
        h.minutes(15)
        assertEquals(UpdatePhase.READY, h.state.phase)
        assertEquals(0, h.stored().failures)
    }
}
