package io.github.tycho991.openframe.core.testing

import io.github.tycho991.openframe.core.platform.ApkVerdict
import io.github.tycho991.openframe.core.platform.AppInstaller
import io.github.tycho991.openframe.core.platform.InstallResult
import io.github.tycho991.openframe.core.update.OfficialCertificate
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Android's `PackageInstaller` for tests: records the calls and lets the test answer the dialog. Its sessions outlive a
 * [TestFrame], as Android's outlive the app, so that a test can start a new frame with the same installer. Like
 * Android, it reports [InstallResult.Cancelled] for a session that is abandoned.
 */
class FakeAppInstaller(
    @Volatile var certificate: String? = OfficialCertificate.SHA256,
    @Volatile var allowed: Boolean = true,
) : AppInstaller {
    /** Where results go, normally `UpdateManager.onInstallResult` of the running frame. */
    @Volatile var onResult: ((Int, InstallResult) -> Unit)? = null

    /** `create 1`, `commit 1`, `abandon 1`, in order. */
    val calls = CopyOnWriteArrayList<String>()

    /** What [verify] answers. */
    @Volatile var verdict: (File) -> ApkVerdict = { ApkVerdict.Ok }

    /** Thrown by the next [commit]. */
    @Volatile var commitFailure: Exception? = null

    /** [createSession] pauses this long after it opened the session, which widens the window before the commit. */
    @Volatile var pauseAfterCreateMs: Long = 0

    /** Android answers this at once, while [commit] still runs. */
    @Volatile var answerOnCommit: InstallResult? = null

    /** The bytes committed to each session; written on the core's IO thread, read on the test's or control's. */
    val committed: MutableMap<Int, ByteArray> = ConcurrentHashMap()

    /** Guards [commitOrder]; [committedNow] is signalled at every commit, for [awaitCommitted]. */
    private val commits = ReentrantLock()
    private val committedNow = commits.newCondition()

    /** The sessions in the order they were committed. */
    private val commitOrder = CopyOnWriteArrayList<Int>()

    private val open = java.util.concurrent.ConcurrentSkipListSet<Int>()
    private val ids = AtomicInteger(1)

    override val installAllowed: Boolean get() = allowed

    override fun installedCertificateSha256(): String? = certificate

    override fun verify(apk: File): ApkVerdict = verdict(apk)

    override fun createSession(): Int =
        ids.getAndIncrement().also {
            open += it
            calls += "create $it"
            if (pauseAfterCreateMs > 0) Thread.sleep(pauseAfterCreateMs)
        }

    override fun commit(
        sessionId: Int,
        apk: File,
    ) {
        calls += "commit $sessionId"
        commitFailure?.let {
            commitFailure = null
            throw it
        }
        committed[sessionId] = apk.readBytes()
        commits.withLock {
            commitOrder += sessionId
            committedNow.signalAll()
        }
        answerOnCommit?.let { answer(sessionId, it) }
    }

    /**
     * The open session committed last, waiting up to [timeoutMs] for one; `null` without one. Android shows the
     * installation dialog only once the session is committed, so only such a session has a dialog to answer (design
     * decision 9 of `core-cleanup`). An older open session that nobody answered is skipped, as Android would show only
     * the newest dialog.
     */
    fun awaitCommitted(timeoutMs: Long): Int? {
        var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        commits.withLock {
            while (true) {
                commitOrder.lastOrNull { it in open }?.let { return it }
                if (left <= 0) return null
                left = committedNow.awaitNanos(left)
            }
        }
    }

    override fun openSessions(): Set<Int> = open.toSet()

    override fun abandon(sessionId: Int) {
        calls += "abandon $sessionId"
        if (open.remove(sessionId)) onResult?.invoke(sessionId, InstallResult.Cancelled)
    }

    /** Someone answers the dialog of [sessionId] on the frame, or Android reports its end. */
    fun answer(
        sessionId: Int,
        result: InstallResult,
    ) {
        open.remove(sessionId)
        onResult?.invoke(sessionId, result)
    }
}
