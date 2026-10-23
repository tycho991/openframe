package io.github.tycho991.openframe.core.slideshow

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.settings.Settings
import io.github.tycho991.openframe.core.settings.Transition
import io.github.tycho991.openframe.core.store.JsonStore
import io.github.tycho991.openframe.core.testing.TestClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `slideshow-display`, requirement "Smooth display on weak hardware". */
class TransitionGovernorTest {
    @TempDir
    lateinit var dir: File

    private val log = AppLog(TestClock())
    private val settings = Settings.defaults("PFF_1024")

    private val store by lazy { JsonStore(File(dir, "transitions.json"), StoredTransitionGovernor.serializer(), TestScope(), log) }

    private fun governor() =
        TransitionGovernor(store, log).also {
            it.load()
            it.onSettings(settings)
        }

    private fun TransitionGovernor.smooth() = report(frames = 85, late = 2)

    private fun TransitionGovernor.stuttering() = report(frames = 85, late = 40)

    @Test
    fun `A device that keeps up stays on its transitions`() {
        val g = governor()
        repeat(20) { g.smooth() }
        assertFalse(g.cut.value)
    }

    @Test
    fun `Three late transitions of the last four switch to instant changes`() {
        val g = governor()
        g.stuttering()
        g.stuttering()
        g.smooth()
        assertFalse(g.cut.value)
        g.stuttering()
        assertTrue(g.cut.value)
    }

    @Test
    fun `Two late transitions in a row are not enough`() {
        val g = governor()
        g.stuttering()
        g.stuttering()
        assertFalse(g.cut.value)
    }

    @Test
    fun `An old late transition drops out of the window`() {
        val g = governor()
        g.stuttering()
        g.smooth()
        g.smooth()
        g.smooth()
        g.stuttering()
        g.stuttering()
        assertFalse(g.cut.value)
    }

    @Test
    fun `The limit is a fifth of the frames`() {
        val g = governor()
        repeat(4) { g.report(frames = 100, late = 20) }
        assertFalse(g.cut.value)
        repeat(4) { g.report(frames = 100, late = 21) }
        assertTrue(g.cut.value)
    }

    @Test
    fun `A transition with few frames says nothing`() {
        val g = governor()
        repeat(10) { g.report(frames = 5, late = 5) }
        assertFalse(g.cut.value)
    }

    @Test
    fun `The fallback survives a restart`() {
        val g = governor()
        repeat(3) { g.stuttering() }
        runBlocking { store.flush() }
        assertTrue(g.cut.value)
        val again = governor()
        assertTrue(again.cut.value)
    }

    @Test
    fun `Another transition or duration tries again`() {
        val g = governor()
        repeat(3) { g.stuttering() }
        assertTrue(g.cut.value)
        g.onSettings(settings)
        assertTrue(g.cut.value)
        g.onSettings(settings.copy(transitionDurationMs = settings.transitionDurationMs + 500))
        assertFalse(g.cut.value)
        g.onSettings(settings.copy(transition = Transition.SLIDE))
        repeat(3) { g.stuttering() }
        assertTrue(g.cut.value)
    }

    @Test
    fun `While photos change instantly nothing is measured`() {
        val g = governor()
        repeat(3) { g.stuttering() }
        repeat(10) { g.smooth() }
        assertTrue(g.cut.value)
    }
}
