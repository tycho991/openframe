package io.github.tycho991.openframe.core.platform

import io.github.tycho991.openframe.core.update.UpdateErrorCode
import java.io.File

/** What [AppInstaller.verify] says about a downloaded APK. */
sealed interface ApkVerdict {
    data object Ok : ApkVerdict

    /** Not installable over the running app: [code] is `update_invalid_apk` or `update_signature`. */
    data class Rejected(
        val code: UpdateErrorCode,
        val detail: String,
    ) : ApkVerdict
}

/** How Android answered an installation session. */
sealed interface InstallResult {
    /** Someone cancelled the dialog, or the session was abandoned. */
    data object Cancelled : InstallResult

    data class Failed(
        val message: String,
    ) : InstallResult

    /** Installed; usually never arrives, because Android ends the app first. */
    data object Success : InstallResult
}

/**
 * Installing a new version of the app with Android's installation dialog (design decision 6 of `app-auto-update`). In
 * :android a `PackageInstaller` session; a fake in tests. Results reach `UpdateManager.onInstallResult` with the session
 * ID, from :android through its receiver.
 */
interface AppInstaller {
    /** Android allows openframe to install apps (apps from unknown sources); read afresh on each call. */
    val installAllowed: Boolean

    /** SHA-256 of the installed app's signing certificate as lowercase hex, `null` if it cannot be read. */
    fun installedCertificateSha256(): String?

    /** Package ID, a higher version code, no debug build and the installed app's certificate. */
    fun verify(apk: File): ApkVerdict

    /** Creates a session and returns its ID, without opening anything yet. */
    fun createSession(): Int

    /** Copies [apk] into the session and commits it, which opens the dialog. Blocking I/O, never on the main thread. */
    fun commit(
        sessionId: Int,
        apk: File,
    )

    /** The sessions of this app that are still open, also from before a restart of the app. */
    fun openSessions(): Set<Int>

    /** Discards the session; Android reports it as cancelled afterwards. */
    fun abandon(sessionId: Int)

    companion object {
        /** A device that cannot install: not allowed, no certificate, so the updater stays off. */
        val None: AppInstaller =
            object : AppInstaller {
                override val installAllowed = false

                override fun installedCertificateSha256(): String? = null

                override fun verify(apk: File): ApkVerdict = ApkVerdict.Rejected(UpdateErrorCode.UPDATE_SIGNATURE, "No installer")

                override fun createSession(): Int = throw UnsupportedOperationException("No installer")

                override fun commit(
                    sessionId: Int,
                    apk: File,
                ) = throw UnsupportedOperationException("No installer")

                override fun openSessions(): Set<Int> = emptySet()

                override fun abandon(sessionId: Int) {}
            }
    }
}
