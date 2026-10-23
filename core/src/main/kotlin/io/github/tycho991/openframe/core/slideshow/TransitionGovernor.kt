package io.github.tycho991.openframe.core.slideshow

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.settings.Settings
import io.github.tycho991.openframe.core.store.JsonStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
data class StoredTransitionGovernor(
    /** The transition and duration the verdicts are about: another one tries again. */
    val key: String = "",
    /** Whether the last transitions were late, oldest first, at most [TransitionGovernor.WINDOW]. */
    val recent: List<Boolean> = emptyList(),
    val cut: Boolean = false,
)

/**
 * Decides whether a device can show transitions. The display reports how many frames of each finished transition
 * arrived late; when most of the recent transitions were mostly late, photos change instantly ([cut]) until the
 * transition or its duration is changed. The setting itself is never touched.
 */
class TransitionGovernor(
    private val store: JsonStore<StoredTransitionGovernor>,
    private val log: AppLog,
) {
    private var state = StoredTransitionGovernor()
    private val _cut = MutableStateFlow(false)

    /** Photos change instantly because the display could not keep up with the transition. */
    val cut: StateFlow<Boolean> = _cut.asStateFlow()

    @Synchronized
    fun load() {
        state = store.load() ?: StoredTransitionGovernor()
        _cut.value = state.cut
    }

    /** The settings changed (or were loaded): another transition or duration starts over. */
    @Synchronized
    fun onSettings(settings: Settings) {
        val key = "${settings.transition}/${settings.transitionDurationMs}"
        if (key == state.key) return
        val wasCut = state.cut
        update(StoredTransitionGovernor(key = key))
        if (wasCut) log.i(TAG, "Transition setting changed, trying it again")
    }

    /** A transition ended with [late] of [frames] frames too late. Transitions that were cut short are not reported. */
    @Synchronized
    fun report(
        frames: Int,
        late: Int,
    ) {
        if (state.cut || frames < MIN_FRAMES) return
        val recent = (state.recent + (late * LATE_DENOMINATOR > frames)).takeLast(WINDOW)
        val cut = recent.count { it } >= BAD_OF_WINDOW
        update(state.copy(recent = recent, cut = cut))
        if (cut) log.w(TAG, "$late of $frames frames of the transition were late again, photos change instantly from now on")
    }

    private fun update(next: StoredTransitionGovernor) {
        state = next
        _cut.value = next.cut
        store.save(next)
    }

    companion object {
        /** The last transitions that count. */
        const val WINDOW = 4

        /** So many of them must have been late. */
        const val BAD_OF_WINDOW = 3

        /** More than 1/5 of the frames late makes a transition late. */
        const val LATE_DENOMINATOR = 5

        /** Fewer frames say nothing about the device. */
        const val MIN_FRAMES = 10
        private const val TAG = "transitions"
    }
}
