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

    private fun governor(with: Settings = settings) =
        TransitionGovernor(store, log).also {
            it.load()
            it.onSettings(with)
        }

    @Test
    fun `A device that blends in a third of a frame keeps its transitions`() {
        val g = governor()
        assertTrue(g.judgementDue.value)
        g.judge(frameMs = 5.4f, refreshMs = 16.7f)
        assertFalse(g.cut.value)
        assertFalse(g.judgementDue.value)
    }

    @Test
    fun `A device that needs most of a frame to blend changes photos instantly`() {
        val g = governor()
        g.judge(frameMs = 13.9f, refreshMs = 17.65f)
        assertTrue(g.cut.value)
        assertFalse(g.judgementDue.value)
    }

    @Test
    fun `The limit is half a refresh interval`() {
        val g = governor()
        g.judge(frameMs = 8.35f, refreshMs = 16.7f)
        assertFalse(g.cut.value)
        val other = governor(settings.copy(transitionDurationMs = 1000))
        other.judge(frameMs = 8.4f, refreshMs = 16.7f)
        assertTrue(other.cut.value)
    }

    @Test
    fun `A device that cannot be measured keeps its transitions`() {
        val g = governor()
        g.judge(frameMs = null, refreshMs = 16.7f)
        assertFalse(g.cut.value)
        assertFalse(g.judgementDue.value)
    }

    @Test
    fun `Without a transition nothing is measured`() {
        val g = governor(settings.copy(transition = Transition.NONE))
        assertFalse(g.judgementDue.value)
        g.onSettings(settings.copy(transition = Transition.NONE, transitionDurationMs = 0))
        assertFalse(g.judgementDue.value)
        g.onSettings(settings)
        assertTrue(g.judgementDue.value)
    }

    @Test
    fun `The verdict survives a restart and is not measured again`() {
        governor().judge(frameMs = 13.9f, refreshMs = 17.65f)
        runBlocking { store.flush() }
        val again = governor()
        assertTrue(again.cut.value)
        assertFalse(again.judgementDue.value)
    }

    @Test
    fun `Another transition or duration tries again`() {
        val g = governor()
        g.judge(frameMs = 13.9f, refreshMs = 17.65f)
        g.onSettings(settings)
        assertTrue(g.cut.value)
        g.onSettings(settings.copy(transitionDurationMs = settings.transitionDurationMs + 500))
        assertFalse(g.cut.value)
        assertTrue(g.judgementDue.value)
    }

    @Test
    fun `A second report for the same setting is ignored`() {
        val g = governor()
        g.judge(frameMs = 13.9f, refreshMs = 17.65f)
        g.judge(frameMs = 1f, refreshMs = 17.65f)
        assertTrue(g.cut.value)
    }
}
