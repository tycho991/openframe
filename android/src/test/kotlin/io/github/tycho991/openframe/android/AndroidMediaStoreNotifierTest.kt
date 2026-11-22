package io.github.tycho991.openframe.android

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaScannerConnection
import java.io.File

/** Spec `photo-library`: Batch changes › Large batch: the media store gets one request for all files of a batch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidMediaStoreNotifierTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `Several files are one scan request with all paths`() {
        val requests = mutableListOf<List<String>>()
        val notifier = AndroidMediaStoreNotifier(context) { _, paths -> requests += paths.toList() }
        val files = (1..20).map { File("/sdcard/Pictures/openframe/p$it.jpg") }
        notifier.notifyChanged(files)
        assertEquals(listOf(files.map { it.absolutePath }), requests)
        notifier.notifyChanged(emptyList())
        notifier.notifyChanged(files[0])
        assertEquals(listOf(files.map { it.absolutePath }, listOf(files[0].absolutePath)), requests)
    }

    @Test
    fun `The default asks Android's media scanner`() {
        val files = listOf(File("/sdcard/Pictures/openframe/a.jpg"), File("/sdcard/Pictures/openframe/b.jpg"))
        AndroidMediaStoreNotifier(context).notifyChanged(files)
        assertEquals(files.map { it.absolutePath }.toSet(), ShadowMediaScannerConnection.getSavedPaths())
    }
}
