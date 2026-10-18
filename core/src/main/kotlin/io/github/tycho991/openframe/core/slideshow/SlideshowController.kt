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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random

/** Die Anzeigegrößen, wie die Slideshow sie braucht. */
interface DisplayImages {
    /** Eine fertige Anzeigegröße, notfalls noch die vom alten Format, oder `null`. */
    fun ready(entry: PhotoEntry): File?

    /** Rendert die Anzeigegröße jetzt, vor allem anderen. Wirft, wenn das scheitert. */
    suspend fun render(entry: PhotoEntry): File

    fun prefetch(
        entries: List<PhotoEntry>,
        priority: RenderPriority,
    )
}

/** [DisplayImages] aus dem [RenderCache]. */
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
    /** Noch kein Foto bereit, etwa beim ersten Start mit leerem Cache. */
    data object Loading : SlideState

    /** Keine sichtbaren Fotos. */
    data object Empty : SlideState

    /**
     * [photo] wird mit der Anzeigegröße [file] gezeigt. [step] ist +1 für vorwärts,
     * -1 für zurück und 0 für den ersten Wechsel. [seq] steigt mit jedem Wechsel.
     */
    data class Showing(
        val photo: PhotoEntry,
        val file: File,
        val step: Int,
        val seq: Long,
    ) : SlideState
}

/**
 * Steuert die Slideshow: Reihenfolge, Intervall, Pause und das Überspringen
 * von Fotos, die noch nicht gerendert oder defekt sind. Die Anzeige zeigt nur
 * [state] an und meldet Gesten und Decodierfehler zurück.
 */
class SlideshowController(
    private val visible: StateFlow<List<PhotoEntry>>,
    /** Ob das Foto noch in der Bibliothek ist. Gelöschte aktuelle Fotos wechseln sofort, ausgeblendete beim nächsten Wechsel. */
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
    }

    private val playlist = Playlist(random)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val _state = MutableStateFlow<SlideState>(SlideState.Loading)
    private val _paused = MutableStateFlow(false)
    private var timer: Job? = null
    private var seq = 0L

    val state: StateFlow<SlideState> = _state.asStateFlow()
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _nextUp = MutableStateFlow<Pair<PhotoEntry, File>?>(null)

    /**
     * Das Foto, das beim nächsten Wechsel vorwärts voraussichtlich kommt, mit fertiger
     * Anzeigegröße. Die Anzeige decodiert es vorab, damit der Übergang ohne Wartezeit beginnt.
     */
    val nextUp: StateFlow<Pair<PhotoEntry, File>?> = _nextUp.asStateFlow()

    fun start() {
        scope.launch {
            combine(visible, settings.map { it.order }.distinctUntilChanged()) { photos, order -> Command.Library(photos, order) }
                .collect { commands.send(it) }
        }
        scope.launch {
            for (command in commands) {
                try {
                    handle(command)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.e(TAG, "Fehler in der Slideshow", e)
                }
            }
        }
    }

    /** Wischen nach links: nächstes Foto, das Intervall beginnt neu. */
    fun next() {
        commands.trySend(Command.Step(+1, manual = true))
    }

    /** Wischen nach rechts: voriges Foto, das Intervall beginnt neu. */
    fun previous() {
        commands.trySend(Command.Step(-1, manual = true))
    }

    fun setPaused(paused: Boolean) {
        commands.trySend(Command.Pause(paused))
    }

    fun togglePause() {
        commands.trySend(Command.Pause(null))
    }

    /** Die Anzeige konnte die Datei nicht decodieren: protokollieren und weiter. */
    fun reportUndecodable(photo: PhotoEntry) {
        commands.trySend(Command.Undecodable(photo.id))
    }

    private suspend fun handle(command: Command) {
        when (command) {
            is Command.Library -> {
                onLibrary(command)
            }

            is Command.Step -> {
                show(command.step)
            }

            is Command.Tick -> {
                if (command.seq == seq && !_paused.value) show(+1)
            }

            is Command.Pause -> {
                _paused.value = command.paused ?: !_paused.value
                if (_paused.value) timer?.cancel() else armTimer()
            }

            is Command.Undecodable -> {
                val current = (_state.value as? SlideState.Showing)?.photo
                if (current?.id == command.id) {
                    log.e(TAG, "Foto nicht decodierbar, übersprungen: ${current.path}")
                    show(+1)
                }
            }
        }
    }

    private suspend fun onLibrary(command: Command.Library) {
        val current = (_state.value as? SlideState.Showing)?.photo
        val removed = playlist.update(command.photos, command.order)
        when {
            command.photos.isEmpty() -> {
                timer?.cancel()
                _nextUp.value = null
                _state.value = SlideState.Empty
            }

            _state.value !is SlideState.Showing -> {
                show(0)
            }

            // Gelöscht: sofort weiter. Die Reihenfolge steht schon auf dem nächsten Foto.
            removed && current != null && !exists(current.id) -> {
                show(+1)
            }
        }
    }

    /**
     * Zeigt das nächste ([step] = +1), vorige (-1) oder aktuelle (0) Foto der
     * Reihenfolge, das eine fertige Anzeigegröße hat. Ist gar keine fertig,
     * wird das erste Foto jetzt gerendert und gewartet.
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
        // Kein Foto fertig. Läuft schon eines, bleibt es stehen, bis das nächste fertig ist.
        val showing = (_state.value as? SlideState.Showing)?.photo
        if (showing != null && playlist.moveTo(showing.id) != null) {
            armTimer()
            return
        }
        // Erster Start mit leerem Cache: das erste Foto jetzt rendern und darauf warten.
        _state.value = SlideState.Loading
        for (entry in candidates) {
            playlist.moveTo(entry.id) ?: continue
            try {
                emit(entry, images.render(entry), step)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(TAG, "Foto übersprungen, Rendern fehlgeschlagen: ${entry.path}")
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
        val upcoming = playlist.upcoming(PREFETCH_AHEAD)
        _nextUp.value = upcoming.firstNotNullOfOrNull { e -> images.ready(e)?.let { e to it } }
        images.prefetch(upcoming.take(NEXT_AHEAD), RenderPriority.NEXT)
        images.prefetch(upcoming.drop(NEXT_AHEAD), RenderPriority.PREFETCH)
    }

    private fun armTimer() {
        timer?.cancel()
        if (_paused.value) return
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
