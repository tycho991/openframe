package io.github.tycho991.openframe.android

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.provider.Settings
import io.github.tycho991.openframe.core.platform.ApkVerdict
import io.github.tycho991.openframe.core.platform.AppInstaller
import io.github.tycho991.openframe.core.platform.InstallResult
import io.github.tycho991.openframe.core.update.UpdateErrorCode
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Installs updates with a `PackageInstaller` session, which opens Android's own installation dialog (design decision 7
 * of `app-auto-update`). One code path from API 21 on; [sdkInt] is `Build.VERSION.SDK_INT` outside tests, so that the
 * paths below API 23 can be tested. [archiveInfo] reads a downloaded APK, the system's `getPackageArchiveInfo` outside
 * tests: Robolectric cannot verify a signed archive. Results come back through [InstallStatusReceiver].
 */
class AndroidAppInstaller(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val archiveInfo: (path: String, flags: Int) -> PackageInfo? = { path, flags ->
        context.packageManager.getPackageArchiveInfo(path, flags)
    },
) : AppInstaller {
    private val pm: PackageManager get() = context.packageManager

    /** From API 26 the app's own right to install; below, the device-wide setting for unknown sources. */
    override val installAllowed: Boolean
        @SuppressLint("NewApi") // Gated by sdkInt.
        get() =
            try {
                if (sdkInt >= 26) {
                    pm.canRequestPackageInstalls()
                } else {
                    @Suppress("DEPRECATION")
                    Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
                }
            } catch (e: Exception) {
                false
            }

    override fun installedCertificateSha256(): String? =
        try {
            certificates(pm.getPackageInfo(context.packageName, signatureFlags()))?.singleOrNull()
        } catch (e: Exception) {
            null
        }

    override fun verify(apk: File): ApkVerdict {
        val archive =
            archiveInfo(apk.path, signatureFlags())
                ?: return ApkVerdict.Rejected(UpdateErrorCode.UPDATE_INVALID_APK, "not readable as an APK")
        val installed = pm.getPackageInfo(context.packageName, signatureFlags())
        return check(archive, installed)
    }

    /** Package, version code, debug build and certificates of [archive] against the [installed] app. */
    internal fun check(
        archive: PackageInfo,
        installed: PackageInfo,
    ): ApkVerdict {
        val archiveCode = versionCode(archive)
        val installedCode = versionCode(installed)
        val archiveCerts = certificates(archive)
        val installedCerts = certificates(installed)
        return when {
            archive.packageName != installed.packageName -> {
                ApkVerdict.Rejected(UpdateErrorCode.UPDATE_INVALID_APK, "another app (${archive.packageName})")
            }

            archiveCode <= installedCode -> {
                ApkVerdict.Rejected(UpdateErrorCode.UPDATE_INVALID_APK, "version code $archiveCode is not above $installedCode")
            }

            ((archive.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE) != 0 -> {
                ApkVerdict.Rejected(UpdateErrorCode.UPDATE_INVALID_APK, "a debug build")
            }

            archiveCerts.isNullOrEmpty() -> {
                ApkVerdict.Rejected(UpdateErrorCode.UPDATE_SIGNATURE, "not signed")
            }

            installedCerts.isNullOrEmpty() || archiveCerts.toSet() != installedCerts.toSet() -> {
                ApkVerdict.Rejected(UpdateErrorCode.UPDATE_SIGNATURE, "signed with another certificate")
            }

            else -> {
                ApkVerdict.Ok
            }
        }
    }

    override fun createSession(): Int {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        return pm.packageInstaller.createSession(params)
    }

    override fun commit(
        sessionId: Int,
        apk: File,
    ) {
        pm.packageInstaller.openSession(sessionId).use { session ->
            FileInputStream(apk).use { input ->
                session.openWrite("openframe.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            session.commit(statusIntent(sessionId).intentSender)
        }
    }

    override fun openSessions(): Set<Int> =
        pm.packageInstaller.mySessions
            .map { it.sessionId }
            .toSet()

    /** Discards the session and brings the display to the front, over the installer's stale dialog. */
    override fun abandon(sessionId: Int) {
        pm.packageInstaller.abandonSession(sessionId)
        try {
            context.startActivity(
                Intent(context, DisplayActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        } catch (e: Exception) {
            // From API 29 a start from the background may be refused; the HOME button still leads to the slideshow.
        }
    }

    /** The system fills in the result's extras, so the intent is mutable where Android makes that a choice. */
    @SuppressLint("InlinedApi")
    internal fun statusIntent(sessionId: Int): PendingIntent {
        val intent = Intent(context, InstallStatusReceiver::class.java).setAction(InstallStatusReceiver.ACTION)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (sdkInt >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }

    @SuppressLint("InlinedApi")
    internal fun signatureFlags(): Int {
        @Suppress("DEPRECATION")
        val old = PackageManager.GET_SIGNATURES
        // API 28 may fill only `signatures` for an archive, so both are asked for.
        return if (sdkInt >= 28) old or PackageManager.GET_SIGNING_CERTIFICATES else old
    }

    /** SHA-256 of the signing certificates, from `signingInfo` where there is one, otherwise from `signatures`. */
    @SuppressLint("NewApi") // Gated by sdkInt.
    private fun certificates(info: PackageInfo): List<String>? {
        val signing = if (sdkInt >= 28) info.signingInfo?.apkContentsSigners else null

        @Suppress("DEPRECATION")
        val signatures: Array<Signature>? = signing?.takeIf { it.isNotEmpty() } ?: info.signatures
        return signatures?.map { sha256(it.toByteArray()) }
    }

    @SuppressLint("NewApi") // Gated by sdkInt.
    private fun versionCode(info: PackageInfo): Long =
        if (sdkInt >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * The results of the installation sessions. "Waiting for the user" opens Android's dialog; every other status goes to
 * the updater with its session ID. Not exported: only the system answers through the session's own intent.
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val app = context.applicationContext as FrameApp
        val result =
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    // A dialog of an abandoned session stays in the installer's task; without clearing it, Android
                    // would bring that stale dialog back instead of this session's, and "Install" there crashes it.
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    return
                }

                PackageInstaller.STATUS_SUCCESS -> {
                    InstallResult.Success
                }

                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    InstallResult.Cancelled
                }

                else -> {
                    InstallResult.Failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status")
                }
            }
        // The result may have started the process: the updater keeps it until it has loaded its state.
        FrameService.start(context)
        app.core.update.onInstallResult(sessionId, result)
    }

    companion object {
        const val ACTION = "io.github.tycho991.openframe.INSTALL_STATUS"
    }
}
