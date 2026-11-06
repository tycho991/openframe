package io.github.tycho991.openframe.android

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Looper
import android.provider.Settings
import io.github.tycho991.openframe.core.platform.ApkVerdict
import io.github.tycho991.openframe.core.update.UpdateErrorCode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * Spec `app-update`: Verification before offering and Installation not allowed, on Android (design decision 7 of
 * `app-auto-update`). Robolectric cannot verify a signed archive, so the archive's [PackageInfo] comes from the test;
 * the real parsing is a device test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28, 34])
class AndroidAppInstallerTest {
    private val app = RuntimeEnvironment.getApplication() as FrameApp
    private val pm = app.packageManager
    private val ours = Signature(ByteArray(64) { 1 })
    private val theirs = Signature(ByteArray(64) { 2 })
    private val apk = File(javaClass.getResource("/fixtures/update-0.4.0.apk")!!.toURI())
    private val asked = mutableListOf<Pair<String, Int>>()
    private var archive: PackageInfo? = null
    private val installer by lazy {
        AndroidAppInstaller(app) { path, flags ->
            asked += path to flags
            archive
        }
    }
    private var core: io.github.tycho991.openframe.core.FrameCore? = null

    @Before
    fun installed() {
        @Suppress("DEPRECATION")
        shadowOf(pm).getInternalMutablePackageInfo(app.packageName).apply {
            signatures = arrayOf(ours)
            setCode(this, 300)
        }
    }

    @After
    fun stop() {
        core?.let { runBlocking { it.stop() } }
    }

    private fun setCode(
        info: PackageInfo,
        code: Long,
    ) {
        @Suppress("DEPRECATION")
        info.versionCode = code.toInt()
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode = code
    }

    private fun archive(
        packageName: String = app.packageName,
        code: Long = 400,
        debuggable: Boolean = false,
        signatures: Array<Signature>? = arrayOf(ours),
    ) = PackageInfo().also {
        it.packageName = packageName
        setCode(it, code)
        it.applicationInfo = ApplicationInfo().apply { flags = if (debuggable) ApplicationInfo.FLAG_DEBUGGABLE else 0 }
        @Suppress("DEPRECATION")
        it.signatures = signatures
    }

    private fun verify(info: PackageInfo?): ApkVerdict {
        archive = info
        return installer.verify(apk)
    }

    private fun rejected(
        code: UpdateErrorCode,
        verdict: ApkVerdict,
    ) = assertEquals(code, (verdict as ApkVerdict.Rejected).code)

    @Test
    fun `A newer build with the installed certificate passes`() {
        assertEquals(ApkVerdict.Ok, verify(archive()))
        val (path, flags) = asked.single()
        assertEquals(apk.path, path)
        @Suppress("DEPRECATION")
        assertTrue(flags and PackageManager.GET_SIGNATURES != 0)
        // API 28 may fill only `signatures` for an archive, so both are asked for there.
        assertEquals(Build.VERSION.SDK_INT >= 28, flags and PackageManager.GET_SIGNING_CERTIFICATES != 0)
    }

    @Test
    fun `Another app, a version not above the installed one and a debug build are invalid`() {
        rejected(UpdateErrorCode.UPDATE_INVALID_APK, verify(archive(packageName = "com.example.other")))
        rejected(UpdateErrorCode.UPDATE_INVALID_APK, verify(archive(code = 300)))
        rejected(UpdateErrorCode.UPDATE_INVALID_APK, verify(archive(code = 200)))
        rejected(UpdateErrorCode.UPDATE_INVALID_APK, verify(archive(debuggable = true)))
        rejected(UpdateErrorCode.UPDATE_INVALID_APK, verify(null))
    }

    @Test
    fun `Another certificate or none is a signature failure`() {
        rejected(UpdateErrorCode.UPDATE_SIGNATURE, verify(archive(signatures = arrayOf(theirs))))
        rejected(UpdateErrorCode.UPDATE_SIGNATURE, verify(archive(signatures = arrayOf(ours, theirs))))
        rejected(UpdateErrorCode.UPDATE_SIGNATURE, verify(archive(signatures = null)))
        rejected(UpdateErrorCode.UPDATE_SIGNATURE, verify(archive(signatures = emptyArray())))
    }

    @Test
    fun `The installed certificate as SHA-256`() {
        val expected = MessageDigest.getInstance("SHA-256").digest(ours.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(expected, installer.installedCertificateSha256())
    }

    @Test
    fun `Installing is allowed by the setting for unknown sources below API 26, by the app's own right from 26`() {
        val resolver = app.contentResolver

        @Suppress("DEPRECATION")
        val setting = Settings.Secure.INSTALL_NON_MARKET_APPS
        if (Build.VERSION.SDK_INT < 26) {
            for (sdk in listOf(21, Build.VERSION.SDK_INT)) {
                val old = AndroidAppInstaller(app, sdkInt = sdk)
                Settings.Secure.putInt(resolver, setting, 0)
                assertFalse(old.installAllowed)
                Settings.Secure.putInt(resolver, setting, 1)
                assertTrue(old.installAllowed)
            }
        } else {
            Settings.Secure.putInt(resolver, setting, 1)
            shadowOf(pm).setCanRequestPackageInstalls(false)
            assertFalse(installer.installAllowed)
            shadowOf(pm).setCanRequestPackageInstalls(true)
            assertTrue(installer.installAllowed)
        }
    }

    @Test
    fun `The result intent is mutable from API 31, for the extras the system fills in`() {
        val pending = installer.statusIntent(7)
        val flags = shadowOf(pending).flags
        assertEquals(Build.VERSION.SDK_INT >= 31, flags and PendingIntent.FLAG_MUTABLE != 0)
        assertEquals(InstallStatusReceiver::class.java.name, shadowOf(pending).savedIntent.component?.className)
    }

    @Test
    fun `Waiting for the user opens Android's dialog in a fresh task`() {
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        InstallStatusReceiver().onReceive(
            app,
            Intent(InstallStatusReceiver.ACTION)
                .putExtra(PackageInstaller.EXTRA_SESSION_ID, 7)
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
                .putExtra(Intent.EXTRA_INTENT, confirm),
        )
        val started = shadowOf(app).nextStartedActivity
        assertEquals("android.content.pm.action.CONFIRM_INSTALL", started.action)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        // The stale dialog of an abandoned session must not come back in its place.
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
    }

    @Test
    fun `An aborted session reaches the updater as cancelled, with its session ID`() {
        val dir = File(app.cacheDir, "receiver-core")
        val c = TestCore.create(dir)
        core = c
        app.coreFactory = { c }
        runBlocking { c.start() }
        InstallStatusReceiver().onReceive(
            app,
            Intent(InstallStatusReceiver.ACTION)
                .putExtra(PackageInstaller.EXTRA_SESSION_ID, 7)
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED),
        )
        // Kept until the updater has loaded its state, then applied: dropped, because no dialog of session 7 is open.
        val deadline = System.currentTimeMillis() + 5000
        var line: String? = null
        while (line == null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            line =
                c.log
                    .recent(100)
                    .map { it.message }
                    .firstOrNull { "installation session 7" in it }
            if (line == null) Thread.sleep(20)
        }
        assertNotNull("result delivered", line)
        assertTrue(line, line!!.startsWith("Result Cancelled of installation session 7"))
        assertEquals(FrameService::class.java.name, shadowOf(app).nextStartedService.component?.className)
    }
}
