package io.github.tycho991.openframe.core.slideshow

import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.RenderCache
import io.github.tycho991.openframe.core.library.RenderPriority
import io.github.tycho991.openframe.core.library.RenderSize
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.settings.Order
import io.github.tycho991.openframe.core.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
    /** A finished display size, if need be still the one from the old format or section, or `null`. */
    fun ready(entry: PhotoEntry): File?

    /** The finished display size for the entry's current presentation, or `null` while it is not rendered. */
    fun current(entry: PhotoEntry): File?

    /** Renders the display size with [priority], by default before everything else. Throws if that fails. */
    suspend fun render(
        entry: PhotoEntry,
        priority: RenderPriority = RenderPriority.DEMAND,
    ): File

    fun prefetch(
        entries: List<PhotoEntry>,
        priority: RenderPriority,
    )
}

/** [DisplayImages] from the [RenderCache]. */
class CachedDisplayImages(
    private val cache: RenderCache,
) : DisplayImages {
    override fun ready(entry: PhotoEntry): File? = current(entry) ?: cache.peekAnyDisplay(entry)

    override fun current(entry: PhotoEntry): File? = cache.peek(entry, RenderSize.DISPLAY)

    override suspend fun render(
        entry: PhotoEntry,
        priority: RenderPriority,
    ): File = cache.get(entry, RenderSize.DISPLAY, priority)

    override fun prefetch(
        entries: List<PhotoEntry>,
        priority: RenderPriority,
    ) = cache.prefetch(entries, priority)
}

/** A command from the API (spec `slideshow-display`, "Operating the slideshow through the API"). */
enum class SlideshowAction(
    val apiName: String,
) {
    NEXT("next"),
    PREVIOUS("previous"),
    PAUSE("pause"),
    RESUME("resume"),
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

        /** The timer [timer] ran out; an older timer's tick is ignored. */
        data class Tick(
            val timer: Long,
        ) : Command

        /** The current display size of [photo] is ready, see [refreshShown]. */
        data class Rendered(
            val photo: PhotoEntry,
            val file: File,
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

        /** [command], and [done] completes once it has been handled, also if handling it failed. */
        data class Awaited(
            val command: Command,
            val done: CompletableDeferred<Unit>,
        ) : Command
    }

    private val playlist = Playlist(random)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val _state = MutableStateFlow<SlideState>(SlideState.Loading)
    private val _paused = MutableStateFlow(false)
    private val _resting = MutableStateFlow(false)
    private var timer: Job? = null

    /** Counts the timers, so that a tick of a cancelled one is ignored. Apart from [seq]: switching a part keeps it. */
    private var timerGeneration = 0L
    private var seq = 0L

    /** The render of the current part of the photo on screen and the entry it is for, see [refreshShown]. */
    private var refresh: Job? = null
    private var refreshFor: PhotoEntry? = null

    /** Fill mode or orientation changed while resting: the photo that stays is rendered again on wake. */
    private var presentationChangedWhileResting = false

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
                } catch (e: Throwable) {
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

    /**
     * A command from the API: the same as the gestures and the hint, but returns only once the command has been
     * handled, so that [state] and [paused] show its effect. A step while resting is ignored as from the display.
     */
    suspend fun control(action: SlideshowAction) {
        val command =
            when (action) {
                SlideshowAction.NEXT -> Command.Step(+1, manual = true)
                SlideshowAction.PREVIOUS -> Command.Step(-1, manual = true)
                SlideshowAction.PAUSE -> Command.Pause(true)
                SlideshowAction.RESUME -> Command.Pause(false)
            }
        val done = CompletableDeferred<Unit>()
        commands.send(Command.Awaited(command, done))
        done.await()
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
                if (command.timer == timerGeneration && !_paused.value && !isResting) show(+1)
            }

            is Command.Rendered -> {
                val shown = _state.value as? SlideState.Showing
                // Only for the photo still on screen and the entry the render was for: a newer section renders anew.
                if (shown != null && !isResting && command.photo == refreshFor && shown.photo.id == command.photo.id) {
                    refreshFor = null
                    if (shown.file != command.file) switchTo(command.photo, command.file)
                }
            }

            is Command.Rest -> {
                if (command.resting == isResting) return
                _resting.value = command.resting
                if (isResting) timer?.cancel() else wake()
            }

            is Command.Pause -> {
                val paused = command.paused ?: !_paused.value
                // A resume of a running slideshow must not start the interval over.
                if (paused == _paused.value) return
                _paused.value = paused
                if (paused) timer?.cancel() else armTimer()
            }

            is Command.Undecodable -> {
                val current = (_state.value as? SlideState.Showing)?.photo
                if (current?.id == command.id && !isResting) {
                    log.e(TAG, "Photo not decodable, skipped: ${current.path}")
                    show(+1)
                }
            }

            is Command.Presentation -> {
                if (isResting) {
                    presentationChangedWhileResting = true
                } else if (_state.value is SlideState.Showing) {
                    prefetchUpcoming()
                    refreshShown()
                }
            }

            is Command.Awaited -> {
                try {
                    handle(command.command)
                } finally {
                    command.done.complete(Unit)
                }
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

            // A new section changes the display size: the photo on screen switches to it once it is rendered (spec
            // `slideshow-display`, "Current part of the photo on screen"), the others are rendered before their turn.
            else -> {
                prefetchUpcoming()
                val updated = command.photos.firstOrNull { it.id == current?.id }
                if (updated != null && updated != current) refreshShown(updated)
            }
        }
    }

    /**
     * The display came on: the photo from before stays and its interval starts over, also while paused (spec
     * `display-schedule`, "Slideshow rests while the display is off"). A photo deleted or hidden meanwhile has left the
     * order, which then already points to the next photo.
     */
    private suspend fun wake() {
        val current = (_state.value as? SlideState.Showing)?.photo
        val presentationChanged = presentationChangedWhileResting
        presentationChangedWhileResting = false
        when {
            playlist.size == 0 -> {
                _nextUp.value = null
                _state.value = SlideState.Empty
            }

            current == null -> {
                show(0)
            }

            playlist.current?.id != current.id || !exists(current.id) -> {
                show(+1)
            }

            else -> {
                // Nothing was rendered ahead while resting, and the library may have changed.
                prefetchUpcoming()
                armTimer()
                if (presentationChanged) refreshShown()
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
                // The file may be of an older section, fill mode or orientation: the current one follows
                refreshShown()
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
            } catch (e: Throwable) {
                // An OutOfMemoryError skips the photo as well (spec `slideshow-display`, "Skip faulty files").
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

    /**
     * Brings the photo on screen to its current part, also while paused (spec `slideshow-display`, "Current part of the
     * photo on screen"): a step may have shown a display size of an older section, fill mode or orientation, and a new
     * section or presentation makes the shown one old. If the current part is not rendered yet, it is rendered without
     * blocking the commands and [Command.Rendered] switches to it; the older part stays until then. [photo] is the
     * library's entry of the photo on screen, newer than the shown one after a section changed.
     */
    private fun refreshShown(photo: PhotoEntry? = null) {
        val shown = _state.value as? SlideState.Showing ?: return
        if (isResting) return
        val entry = photo ?: shown.photo
        val current = images.current(entry)
        if (current == shown.file && entry == shown.photo) return
        refresh?.cancel()
        refreshFor = null
        if (current != null) {
            if (current != shown.file) switchTo(entry, current) else _state.value = shown.copy(photo = entry)
            return
        }
        refreshFor = entry
        refresh =
            scope.launch {
                val file =
                    try {
                        images.render(entry, RenderPriority.NEXT)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // An OutOfMemoryError as well: the older part stays on screen
                        log.w(TAG, "Current part not rendered, the older one stays: ${entry.path}")
                        return@launch
                    }
                commands.send(Command.Rendered(entry, file))
            }
    }

    /** The same photo with another display size; the interval that is running goes on. */
    private fun switchTo(
        photo: PhotoEntry,
        file: File,
    ) {
        seq++
        _state.value = SlideState.Showing(photo, file, 0, seq)
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
        val mine = ++timerGeneration
        val interval = settings.value.intervalSeconds * 1000L
        timer =
            scope.launch {
                delay(interval)
                commands.send(Command.Tick(mine))
            }
    }

    private companion object {
        const val TAG = "slideshow"
        const val NEXT_AHEAD = 2
        const val PREFETCH_AHEAD = 10
    }
}
