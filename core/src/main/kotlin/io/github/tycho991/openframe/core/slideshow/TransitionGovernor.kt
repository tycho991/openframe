package io.github.tycho991.openframe.core.slideshow

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.settings.Settings
import io.github.tycho991.openframe.core.settings.Transition
import io.github.tycho991.openframe.core.store.JsonStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
data class StoredTransitionGovernor(
    /** The transition and duration the verdict is about: another one is judged anew. */
    val key: String = "",
    /** Whether the device was measured for [key]. */
    val judged: Boolean = false,
    val cut: Boolean = false,
)

/**
 * Decides whether a device can show transitions. The display cannot see from inside the slideshow that its GPU is
 * behind (the transitions run on the render thread), so it measures what one blended frame of the screen's size costs
 * and reports it with [judge]. When that takes more than half a refresh interval there is no room left for the rest of
 * the frame and photos change instantly ([cut]) until the transition or its duration is changed. The setting itself is
 * never touched.
 */
class TransitionGovernor(
    private val store: JsonStore<StoredTransitionGovernor>,
    private val log: AppLog,
) {
    private var state = StoredTransitionGovernor()
    private var transition = Transition.NONE
    private val _cut = MutableStateFlow(false)
    private val _judgementDue = MutableStateFlow(false)

    /** Photos change instantly because the display could not keep up with the transition. */
    val cut: StateFlow<Boolean> = _cut.asStateFlow()

    /** The display is to measure and call [judge]. */
    val judgementDue: StateFlow<Boolean> = _judgementDue.asStateFlow()

    @Synchronized
    fun load() {
        state = store.load() ?: StoredTransitionGovernor()
        _cut.value = state.cut
    }

    /** The settings changed (or were loaded): another transition or duration starts over. */
    @Synchronized
    fun onSettings(settings: Settings) {
        transition = if (settings.transitionDurationMs <= 0) Transition.NONE else settings.transition
        val key = "${settings.transition}/${settings.transitionDurationMs}"
        if (key != state.key) {
            if (state.cut) log.i(TAG, "Transition setting changed, trying it again")
            update(StoredTransitionGovernor(key = key))
        }
        _judgementDue.value = !state.judged && transition != Transition.NONE
    }

    /**
     * One blended frame took [frameMs] on this device, with a refresh interval of [refreshMs]. `null` if the device
     * could not be measured: then the transitions stay.
     */
    @Synchronized
    fun judge(
        frameMs: Float?,
        refreshMs: Float,
    ) {
        if (state.judged) return
        val cut = frameMs != null && frameMs > refreshMs * LIMIT
        update(state.copy(judged = true, cut = cut))
        _judgementDue.value = false
        if (frameMs == null) {
            log.w(TAG, "The display could not be measured, transitions stay")
        } else if (cut) {
            log.w(TAG, "A blended frame takes $frameMs ms of $refreshMs ms, photos change instantly from now on")
        } else {
            log.i(TAG, "A blended frame takes $frameMs ms of $refreshMs ms, transitions stay")
        }
    }

    private fun update(next: StoredTransitionGovernor) {
        state = next
        _cut.value = next.cut
        store.save(next)
    }

    companion object {
        /** A blended frame may take half of a refresh interval: measured 0.33 on a device that fades well, 0.79 on one that does not. */
        const val LIMIT = 0.5f
        private const val TAG = "transitions"
    }
}
