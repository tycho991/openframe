package io.github.tycho991.openframe.android

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Spec `app-release`: Single version source. `GET /info` reports the build's version name, taken from version.txt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppVersionTest {
    @Test
    fun `The app version is the version name from version txt`() {
        // Unit tests run in the module directory, android/.
        val expected = File("../version.txt").readText().trim()
        assertEquals(expected, AndroidPlatformInfo(RuntimeEnvironment.getApplication()).appVersion)
    }
}
