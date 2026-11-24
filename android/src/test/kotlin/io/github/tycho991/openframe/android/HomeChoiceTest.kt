package io.github.tycho991.openframe.android

import io.github.tycho991.openframe.android.HomeChoice.Action
import io.github.tycho991.openframe.android.HomeChoice.Component
import io.github.tycho991.openframe.android.HomeChoice.Record
import io.github.tycho991.openframe.android.HomeChoice.Status
import io.github.tycho991.openframe.core.health.RunEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec `kiosk-host` › HOME choice after an update: the pure decisions of [HomeChoice] (design decisions 6 and 8 of
 * `core-cleanup`), without Robolectric.
 */
class HomeChoiceTest {
    private val own = "io.github.tycho991.openframe"
    private val display = Component(own, "$own.android.DisplayActivity")
    private val launcher = Component("com.example.launcher", "com.example.launcher.Main")
    private val resolver = Component("android", "com.android.internal.app.ResolverActivity")
    private val vendorResolver = Component("com.vendor.resolver", "com.vendor.resolver.Chooser")

    @Test
    fun `What Android does when the Home button is pressed`() {
        // resolved, candidates -> status
        val table =
            listOf(
                Triple(resolver, listOf(display, launcher), Status.ASKS),
                Triple(vendorResolver, listOf(display, launcher), Status.ASKS),
                Triple(null, listOf(display, launcher), Status.ASKS),
                Triple(display, listOf(display, launcher), Status.OWN),
                Triple(launcher, listOf(display, launcher), Status.OTHER),
                Triple(display, listOf(display), Status.OWN),
                Triple(resolver, listOf(display), Status.OWN),
                Triple(launcher, listOf(launcher), Status.NONE),
                Triple(resolver, listOf(launcher, Component("com.example.other", "com.example.other.Main")), Status.NONE),
                Triple(null, emptyList(), Status.NONE),
            )
        for ((resolved, candidates, expected) in table) {
            assertEquals("$resolved of $candidates", expected, HomeChoice.status(resolved, candidates, own))
        }
    }

    @Test
    fun `What the display does with the choice`() {
        val install = "1400:1700000000000"
        val pending = Record(install, done = false)
        // record, status, safe mode, display showing -> action
        val table =
            listOf(
                Triple(null, Status.ASKS, false to true) to Action.NOTHING,
                Triple(Record("1300:1600000000000", done = false), Status.ASKS, false to true) to Action.NOTHING,
                Triple(Record(install, done = true), Status.ASKS, false to true) to Action.NOTHING,
                Triple(pending, Status.ASKS, false to true) to Action.OPEN,
                Triple(pending, Status.ASKS, true to true) to Action.WAIT,
                // Display off, in a dark wake or the slideshow not in front (not resumed): not showing.
                Triple(pending, Status.ASKS, false to false) to Action.WAIT,
                Triple(pending, Status.ASKS, true to false) to Action.WAIT,
                Triple(pending, Status.OWN, false to true) to Action.DROP,
                Triple(pending, Status.OTHER, false to true) to Action.DROP,
                Triple(pending, Status.NONE, false to true) to Action.DROP,
                Triple(pending, Status.OWN, true to false) to Action.DROP,
                Triple(Record(install, done = true), Status.OWN, false to true) to Action.NOTHING,
            )
        for ((input, expected) in table) {
            val (record, status, flags) = input
            val (safe, showing) = flags
            assertEquals(input.toString(), expected, HomeChoice.decide(record, install, status, safe, showing))
        }
    }

    @Test
    fun `Armed only at the first start of a new installation`() {
        val install = "1400:1700000000000"
        assertTrue(HomeChoice.arms(RunEnd.REPLACED, null, install))
        assertTrue("a record of the installation before", HomeChoice.arms(RunEnd.REPLACED, Record("1300:1", done = true), install))
        assertFalse("a first installation", HomeChoice.arms(RunEnd.FIRST, null, install))
        for (end in listOf(RunEnd.REBOOTED, RunEnd.PLANNED, RunEnd.CRASHED, RunEnd.DIED)) {
            assertFalse(end.name, HomeChoice.arms(end, null, install))
        }
        assertFalse("already armed", HomeChoice.arms(RunEnd.REPLACED, Record(install, done = false), install))
        assertFalse("already done", HomeChoice.arms(RunEnd.REPLACED, Record(install, done = true), install))
        assertFalse("no installation known", HomeChoice.arms(RunEnd.REPLACED, null, ""))
    }

    @Test
    fun `The record per installation`() {
        val install = "1400:1700000000000"
        assertEquals("$install|pending", Record(install, done = false).encode())
        assertEquals("$install|done", Record(install, done = true).encode())
        assertEquals(Record(install, done = false), Record.parse("$install|pending"))
        assertEquals(Record(install, done = true), Record.parse("$install|done"))
        assertNull(Record.parse(null))
        assertNull(Record.parse("garbage"))
        assertNull(Record.parse("$install|maybe"))
    }
}
