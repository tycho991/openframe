package io.github.tycho991.openframe.core.testing

import io.github.tycho991.openframe.core.platform.InstallResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.concurrent.thread

/** The fake installer answers only a dialog that is open, as Android shows one only after the commit. */
class FakeAppInstallerTest {
    @TempDir
    lateinit var dir: File

    private val apk by lazy { File(dir, "app.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) } }

    @Test
    fun `The newest of the committed open sessions`() {
        val installer = FakeAppInstaller()
        val first = installer.createSession()
        val second = installer.createSession()
        installer.commit(first, apk)
        installer.commit(second, apk)
        assertEquals(second, installer.awaitCommitted(0))
        installer.answer(second, InstallResult.Cancelled)
        assertEquals(first, installer.awaitCommitted(0), "an older dialog nobody answered")
    }

    @Test
    fun `A commit made on another thread is seen`() {
        val installer = FakeAppInstaller()
        val session = installer.createSession()
        val committer =
            thread {
                Thread.sleep(200)
                installer.commit(session, apk)
            }
        val started = System.nanoTime()
        assertEquals(session, installer.awaitCommitted(5_000))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "woken by the commit")
        committer.join()
    }

    @Test
    fun `Nothing after the timeout without a committed open session`() {
        val installer = FakeAppInstaller()
        installer.createSession()
        val started = System.nanoTime()
        assertNull(installer.awaitCommitted(200))
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 200)
        val answered = installer.createSession()
        installer.commit(answered, apk)
        installer.answer(answered, InstallResult.Cancelled)
        assertNull(installer.awaitCommitted(0), "an answered dialog is gone")
    }
}
