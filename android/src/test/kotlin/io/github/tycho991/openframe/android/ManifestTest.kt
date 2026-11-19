package io.github.tycho991.openframe.android

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

/** Spec `nextcloud-source`: Plain http on every Android version (design decision 13 of `nextcloud-hardening`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"

    /** The merged manifest that the build hands the unit tests (`isIncludeAndroidResources`). */
    private fun mergedManifest(): File {
        val config = Properties()
        javaClass.classLoader!!.getResourceAsStream("com/android/tools/test_config.properties")!!.use(config::load)
        return File(config.getProperty("android_merged_manifest"))
    }

    @Test
    fun `The merged manifest allows plain http for the app`() {
        val document =
            DocumentBuilderFactory
                .newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(mergedManifest())
        val application = document.getElementsByTagName("application").item(0) as org.w3c.dom.Element
        assertEquals("true", application.getAttributeNS(android, "usesCleartextTraffic"))
        // No network security configuration that could say otherwise.
        assertEquals("", application.getAttributeNS(android, "networkSecurityConfig"))
    }

    @Test
    fun `The installed app has the flag that Android 9 and later read`() {
        val flags = RuntimeEnvironment.getApplication().applicationInfo.flags
        assertTrue(flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0)
    }
}
