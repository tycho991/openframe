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

/**
 * Spec `nextcloud-source`: Plain http on every Android version (design decision 13 of `nextcloud-hardening`); spec
 * `kiosk-host`: Android settings from the device menu (the `<queries>` of design decisions 1a and 2 of
 * `open-android-settings`) and HOME choice after an update (design decision 6 of `core-cleanup`).
 */
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
    fun `The merged manifest lets the app see the Frameo's frame app and Android's settings`() {
        val document =
            DocumentBuilderFactory
                .newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(mergedManifest())
        val queries = document.getElementsByTagName("queries")
        val packages =
            (0 until queries.length).flatMap { q ->
                (queries.item(q) as org.w3c.dom.Element).getElementsByTagName("package").let { l ->
                    (
                        0 until
                            l.length
                    ).map { (l.item(it) as org.w3c.dom.Element).getAttributeNS(android, "name") }
                }
            }
        val actions =
            (0 until queries.length).flatMap { q ->
                (queries.item(q) as org.w3c.dom.Element).getElementsByTagName("action").let { l ->
                    (
                        0 until
                            l.length
                    ).map { (l.item(it) as org.w3c.dom.Element).getAttributeNS(android, "name") }
                }
            }
        assertTrue(packages.toString(), "net.frameo.frame" in packages)
        assertTrue(actions.toString(), "android.settings.SETTINGS" in actions)
    }

    /** Spec `kiosk-host` › HOME choice after an update: other launchers are visible from Android 11 on. */
    @Test
    fun `The merged manifest lets the app see the other HOME apps`() {
        val queries = parsedManifest().getElementsByTagName("queries")
        val intents =
            (0 until queries.length).flatMap { q ->
                (queries.item(q) as org.w3c.dom.Element).getElementsByTagName("intent").let { l ->
                    (0 until l.length).map { l.item(it) as org.w3c.dom.Element }
                }
            }

        fun names(
            intent: org.w3c.dom.Element,
            tag: String,
        ) = intent.getElementsByTagName(tag).let { l ->
            (0 until l.length).map { (l.item(it) as org.w3c.dom.Element).getAttributeNS(android, "name") }
        }
        assertTrue(
            intents.map { names(it, "action") to names(it, "category") }.toString(),
            intents.any {
                names(it, "action") == listOf("android.intent.action.MAIN") &&
                    "android.intent.category.HOME" in names(it, "category")
            },
        )
    }

    private fun parsedManifest(): org.w3c.dom.Document =
        DocumentBuilderFactory
            .newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(mergedManifest())

    /** Spec `slideshow-display` › Orientation of the frame › Switch to portrait, on Android 16 tablets. */
    @Test
    fun `The display opts out of Android 16's orientation restrictions on large screens`() {
        val activities = parsedManifest().getElementsByTagName("activity")
        val display =
            (0 until activities.length)
                .map { activities.item(it) as org.w3c.dom.Element }
                .single { it.getAttributeNS(android, "name").endsWith(".android.DisplayActivity") }
        val properties = display.getElementsByTagName("property")
        val values =
            (0 until properties.length)
                .map { properties.item(it) as org.w3c.dom.Element }
                .associate { it.getAttributeNS(android, "name") to it.getAttributeNS(android, "value") }
        assertEquals("true", values["android.window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY"])
    }

    @Test
    fun `targetSdk stays at most 36 while the orientation opt-out is needed`() {
        val usesSdk = parsedManifest().getElementsByTagName("uses-sdk").item(0) as org.w3c.dom.Element
        val target = usesSdk.getAttributeNS(android, "targetSdkVersion").toInt()
        assertTrue(
            "targetSdk $target: Android ends the orientation opt-out for apps that target 37. Before that bump the " +
                "render size has to come from the real window instead of settings.orientation, or the content has to be " +
                "rotated on large screens (design decision 5 of android-platform-fixes).",
            target <= 36,
        )
    }

    @Test
    fun `The installed app has the flag that Android 9 and later read`() {
        val flags = RuntimeEnvironment.getApplication().applicationInfo.flags
        assertTrue(flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0)
    }
}
