package io.github.tycho991.openframe.core.slideshow

import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.RenderCache
import io.github.tycho991.openframe.core.library.RenderPriority
import io.github.tycho991.openframe.core.library.RenderSize
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.settings.Order
import io.github.tycho991.openframe.core.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random

/** The display sizes as the slideshow needs them. */
interface DisplayImages {
    /** A finished display size, if need be still the one from the old format, or `null`. */
    fun ready(entry: PhotoEntry): File?

    /** Renders the display size now, before everything else. Throws if that fails. */
    suspend fun render(entry: PhotoEntry): File

    fun prefetch(
        entries: List<PhotoEntry>,
        priority: RenderPriority,
    )
}

/** [DisplayImages] from the [RenderCache]. */
class CachedDisplayImages(
    private val cache: RenderCache,
) : DisplayImages {
    override fun ready(entry: PhotoEntry): File? = cache.peek(entry, RenderSize.DISPLAY) ?: cache.peekAnyDisplay(entry)

    override suspend fun render(entry: PhotoEntry): File = cache.get(entry, RenderSize.DISPLAY, RenderPriority.DEMAND)

    override fun prefetch(
        entries: List<PhotoEntry>,
        priority: RenderPriority,
    ) = cache.prefetch(entries, priority)
}

sealed interface SlideState {
    /** No photo ready yet, for example on first start with an empty cache. */
    data object Loading : SlideState

    /** No visible photos. */
    data object Empty : SlideState

    /**
     * [photo] is shown with the display size [file]. [step] is +1 for forward,
     * -1 for back and 0 for the first change. [seq] increases with every change.
     */
    data class Showing(
        val photo: PhotoEntry,
        val file: File,
        val step: Int,
        val seq: Long,
    ) : SlideState
}

/**
 * Controls the slideshow: order, interval, pause and skipping
 * photos that are not yet rendered or are broken. The display only shows
 * [state] and reports gestures and decode errors back.
 */
class SlideshowController(
    private val visible: StateFlow<List<PhotoEntry>>,
    /** Whether the photo is still in the library. Deleted current photos switch immediately, hidden ones at the next change. */
    private val exists: (String) -> Boolean,
    private val settings: StateFlow<Settings>,
    private val images: DisplayImages,
    private val scope: CoroutineScope,
    private val log: AppLog,
    random: Random = Random.Default,
) {
    private sealed interface Command {
        data class Library(
            val photos: List<PhotoEntry>,
            val order: Order,
        ) : Command

        data class Step(
            val step: Int,
            val manual: Boolean,
        ) : Command

        data class Tick(
            val seq: Long,
        ) : Command

        data class Pause(
            val paused: Boolean?,
        ) : Command

        data class Undecodable(
            val id: String,
        ) : Command

        data class Rest(
            val resting: Boolean,
        ) : Command

        /** Fill mode or orientation changed: every display size is a new one. */
        data object Presentation : Command
    }

    private val playlist = Playlist(random)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val _state = MutableStateFlow<SlideState>(SlideState.Loading)
    private val _paused = MutableStateFlow(false)
    private val _resting = MutableStateFlow(false)
    private var timer: Job? = null
    private var seq = 0L

    val state: StateFlow<SlideState> = _state.asStateFlow()
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    /** The display is off: no photo changes and nothing is rendered for it. Apart from the user's pause. */
    val resting: StateFlow<Boolean> = _resting.asStateFlow()
    private val isResting get() = _resting.value

    private val _nextUp = MutableStateFlow<Pair<PhotoEntry, File>?>(null)

    /**
     * The photo that is expected to come at the next forward change, with a finished
     * display size. The display decodes it ahead so that the transition starts without waiting.
     */
    val nextUp: StateFlow<Pair<PhotoEntry, File>?> = _nextUp.asStateFlow()

    fun start() {
        scope.launch {
            combine(visible, settings.map { it.order }.distinctUntilChanged()) { photos, order -> Command.Library(photos, order) }
                .collect { commands.send(it) }
        }
        scope.launch {
            settings
                .map { it.fillMode to it.orientation }
                .distinctUntilChanged()
                .drop(1)
                .collect { commands.send(Command.Presentation) }
        }
        scope.launch {
            for (command in commands) {
                try {
                    handle(command)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.e(TAG, "Error in the slideshow", e)
                }
            }
        }
    }

    /** Swipe left: next photo, the interval starts over. */
    fun next() {
        commands.trySend(Command.Step(+1, manual = true))
    }

    /** Swipe right: previous photo, the interval starts over. */
    fun previous() {
        commands.trySend(Command.Step(-1, manual = true))
    }

    fun setPaused(paused: Boolean) {
        commands.trySend(Command.Pause(paused))
    }

    fun togglePause() {
        commands.trySend(Command.Pause(null))
    }

    /** The display could not decode the file: log it and move on. */
    fun reportUndecodable(photo: PhotoEntry) {
        commands.trySend(Command.Undecodable(photo.id))
    }

    /**
     * Rests while the display is off (spec `display-schedule`): the photo stays and nothing is rendered ahead. When
     * the display comes on, the next photo appears at once, unless the user paused the slideshow.
     */
    fun setResting(resting: Boolean) {
        commands.trySend(Command.Rest(resting))
    }

    private suspend fun handle(command: Command) {
        when (command) {
            is Command.Library -> {
                onLibrary(command)
            }

            is Command.Step -> {
                if (!isResting) show(command.step)
            }

            is Command.Tick -> {
                if (command.seq == seq && !_paused.value && !isResting) show(+1)
            }

            is Command.Rest -> {
                if (command.resting == isResting) return
                _resting.value = command.resting
                if (isResting) timer?.cancel() else wake()
            }

            is Command.Pause -> {
                _paused.value = command.paused ?: !_paused.value
                if (_paused.value) timer?.cancel() else armTimer()
            }

            is Command.Undecodable -> {
                val current = (_state.value as? SlideState.Showing)?.photo
                if (current?.id == command.id && !isResting) {
                    log.e(TAG, "Photo not decodable, skipped: ${current.path}")
                    show(+1)
                }
            }

            is Command.Presentation -> {
                if (!isResting && _state.value is SlideState.Showing) prefetchUpcoming()
            }
        }
    }

    private suspend fun onLibrary(command: Command.Library) {
        val current = (_state.value as? SlideState.Showing)?.photo
        val removed = playlist.update(command.photos, command.order)
        // The order follows the library at once; what the display shows waits until it comes on.
        if (isResting) return
        when {
            command.photos.isEmpty() -> {
                timer?.cancel()
                _nextUp.value = null
                _state.value = SlideState.Empty
            }

            _state.value !is SlideState.Showing -> {
                show(0)
            }

            // Deleted: move on immediately. The order already points to the next photo.
            removed && current != null && !exists(current.id) -> {
                show(+1)
            }

            // A new section changes the display size of that photo: render it before the next change.
            else -> {
                prefetchUpcoming()
            }
        }
    }

    /** The display came on: the next photo at once, or, while paused, the paused photo unless it was deleted. */
    private suspend fun wake() {
        val current = (_state.value as? SlideState.Showing)?.photo
        when {
            playlist.size == 0 -> {
                _nextUp.value = null
                _state.value = SlideState.Empty
            }

            current == null -> {
                show(0)
            }

            !_paused.value || !exists(current.id) -> {
                show(+1)
            }
        }
    }

    /**
     * Shows the next ([step] = +1), previous (-1) or current (0) photo of the
     * order that has a finished display size. If none at all is finished,
     * the first photo is rendered now and waited for.
     */
    private suspend fun show(step: Int) {
        if (playlist.size == 0) return
        val candidates = ArrayList<PhotoEntry>()
        for (i in 0 until playlist.size) {
            val entry =
                (
                    if (i == 0 && step == 0) {
                        playlist.current
                    } else if (step < 0) {
                        playlist.previous()
                    } else {
                        playlist.next()
                    }
                ) ?: return
            val file = images.ready(entry)
            if (file != null) {
                emit(entry, file, step)
                return
            }
            candidates += entry
            images.prefetch(listOf(entry), RenderPriority.NEXT)
        }
        // No photo finished. If one is already showing, it stays until the next is finished.
        val showing = (_state.value as? SlideState.Showing)?.photo
        if (showing != null && playlist.moveTo(showing.id) != null) {
            armTimer()
            return
        }
        // First start with an empty cache: render the first photo now and wait for it.
        _state.value = SlideState.Loading
        for (entry in candidates) {
            playlist.moveTo(entry.id) ?: continue
            try {
                emit(entry, images.render(entry), step)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(TAG, "Photo skipped, rendering failed: ${entry.path}")
            }
        }
    }

    private fun emit(
        entry: PhotoEntry,
        file: File,
        step: Int,
    ) {
        seq++
        _state.value = SlideState.Showing(entry, file, step, seq)
        armTimer()
        prefetchUpcoming()
    }

    private fun prefetchUpcoming() {
        val upcoming = playlist.upcoming(PREFETCH_AHEAD)
        _nextUp.value = upcoming.firstNotNullOfOrNull { e -> images.ready(e)?.let { e to it } }
        images.prefetch(upcoming.take(NEXT_AHEAD), RenderPriority.NEXT)
        images.prefetch(upcoming.drop(NEXT_AHEAD), RenderPriority.PREFETCH)
    }

    private fun armTimer() {
        timer?.cancel()
        if (_paused.value || isResting) return
        val mySeq = seq
        val interval = settings.value.intervalSeconds * 1000L
        timer =
            scope.launch {
                delay(interval)
                commands.send(Command.Tick(mySeq))
            }
    }

    private companion object {
        const val TAG = "slideshow"
        const val NEXT_AHEAD = 2
        const val PREFETCH_AHEAD = 10
    }
}
