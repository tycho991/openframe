package io.github.tycho991.openframe.core

import io.github.tycho991.openframe.core.api.diagnosticsRoutes
import io.github.tycho991.openframe.core.api.infoRoutes
import io.github.tycho991.openframe.core.api.libraryRoutes
import io.github.tycho991.openframe.core.api.pairingRoutes
import io.github.tycho991.openframe.core.api.settingsRoutes
import io.github.tycho991.openframe.core.auth.AccessControl
import io.github.tycho991.openframe.core.auth.ClientRegistry
import io.github.tycho991.openframe.core.auth.ImageSigner
import io.github.tycho991.openframe.core.auth.PairingAdmin
import io.github.tycho991.openframe.core.auth.PairingManager
import io.github.tycho991.openframe.core.auth.StoredClients
import io.github.tycho991.openframe.core.auth.StoredSecret
import io.github.tycho991.openframe.core.http.FrameServer
import io.github.tycho991.openframe.core.http.HttpPipeline
import io.github.tycho991.openframe.core.http.Router
import io.github.tycho991.openframe.core.http.StaticAssets
import io.github.tycho991.openframe.core.identity.FrameIdentity
import io.github.tycho991.openframe.core.library.LocalPhotoSource
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.PhotoIndexStore
import io.github.tycho991.openframe.core.library.PhotoLibrary
import io.github.tycho991.openframe.core.library.RenderCache
import io.github.tycho991.openframe.core.library.RenderSizes
import io.github.tycho991.openframe.core.library.StoredIndex
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.log.LogSink
import io.github.tycho991.openframe.core.log.RotatingFileLog
import io.github.tycho991.openframe.core.platform.AssetSource
import io.github.tycho991.openframe.core.platform.Clock
import io.github.tycho991.openframe.core.platform.MediaEngine
import io.github.tycho991.openframe.core.platform.MediaStoreNotifier
import io.github.tycho991.openframe.core.platform.PlatformInfo
import io.github.tycho991.openframe.core.settings.SettingsRepository
import io.github.tycho991.openframe.core.settings.StoredSettings
import io.github.tycho991.openframe.core.slideshow.CachedDisplayImages
import io.github.tycho991.openframe.core.slideshow.SlideshowController
import io.github.tycho991.openframe.core.store.JsonStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Duration

/** Ordner des Rahmens: Zustand, Cache und der per USB erreichbare Fotoordner. */
data class FrameDirs(
    val state: File,
    val cache: File,
    val photos: File,
)

/**
 * Der Kern des Rahmens. Lädt Einstellungen und Bibliothek, betreibt den
 * HTTP-Server und liefert der Anzeige ihre Daten als [StateFlow]s, ohne HTTP.
 */
class FrameCore(
    private val dirs: FrameDirs,
    val platform: PlatformInfo,
    engine: MediaEngine,
    assets: AssetSource,
    notifier: MediaStoreNotifier,
    val clock: Clock = Clock.System,
    port: Int = DEFAULT_PORT,
    logSinks: List<LogSink> = emptyList(),
    private val scanInterval: Duration = Duration.ofSeconds(15),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val log = AppLog(clock, RotatingFileLog(File(dirs.state, "logs")), sinks = logSinks)

    val settings =
        SettingsRepository(JsonStore(File(dirs.state, "settings.json"), StoredSettings.serializer(), scope, log), platform.model, log)

    val clients = ClientRegistry(JsonStore(File(dirs.state, "clients.json"), StoredClients.serializer(), scope, log), clock)
    val signer = ImageSigner(JsonStore(File(dirs.state, "secret.json"), StoredSecret.serializer(), scope, log), clock)
    val pairing = PairingManager(clients, clock, log)
    private val admin = PairingAdmin(clients, signer, pairing, log)

    private val identityStore = JsonStore(File(dirs.state, "identity.json"), FrameIdentity.serializer(), scope, log)
    private val index = PhotoIndexStore(JsonStore(File(dirs.state, "index.json"), StoredIndex.serializer(), scope, log))
    val localSource = LocalPhotoSource(dirs.photos, engine, index, log)
    val cache =
        RenderCache(
            File(dirs.cache, "renders"),
            engine,
            localSource::fileOf,
            { RenderSizes.screen(platform.screenWidth, platform.screenHeight, settings.settings.value.orientation) },
            { settings.settings.value.cacheBudgetMb * 1024L * 1024L },
            log,
        )
    val library = PhotoLibrary(index, localSource, cache, engine, notifier, platform, log)

    lateinit var identity: FrameIdentity
        private set

    private val _ready = MutableStateFlow(false)

    /** Einstellungen und Bibliothek sind geladen. Die Anzeige wartet nur darauf, nicht auf Server oder Netzwerk. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Die für die Slideshow sichtbaren Fotos, nach Aufnahmedatum. */
    val visiblePhotos: StateFlow<List<PhotoEntry>> =
        index.library
            .map { it.visible }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Steuerung der Slideshow für die Anzeige. */
    val slideshow =
        SlideshowController(
            visiblePhotos,
            { id ->
                index.library.value.photos
                    .containsKey(id)
            },
            settings.settings,
            CachedDisplayImages(cache),
            scope,
            log,
        )

    val router = Router()
    private val pipeline = HttpPipeline(router, AccessControl(clients, signer), log, StaticAssets(assets)::handle)
    val server = FrameServer(port, Dispatchers.IO.limitedParallelism(HANDLER_THREADS), log, pipeline::handle)

    private val scanSignal = Channel<Unit>(Channel.CONFLATED)

    /** Lädt alles, startet die Hintergrundarbeit und den Server. */
    suspend fun start() {
        log.i(TAG, "Start: ${platform.model}, Android ${platform.androidVersion}, App ${platform.appVersion}")
        settings.load()
        clients.load()
        signer.load()
        index.load()
        identity = FrameIdentity.loadOrCreate(identityStore)
        registerRoutes()
        slideshow.start()
        _ready.value = true

        scope.launch { scanLoop() }
        scope.launch {
            while (isActive) {
                pairing.tick()
                delay(1000)
            }
        }
        scope.launch {
            settings.settings
                .map { it.cacheBudgetMb }
                .distinctUntilChanged()
                .drop(1)
                .collect { cache.trim() }
        }
        scope.launch {
            settings.localOnly.drop(1).collect { server.restart(it) }
        }
        server.start(settings.localOnly.value)
    }

    /** Scannt den Fotoordner sofort statt beim nächsten Intervall. */
    fun requestScan() {
        scanSignal.trySend(Unit)
    }

    /** Modus „nur lokal“, nur über das Gerätemenü. */
    fun setLocalOnly(enabled: Boolean) = settings.setLocalOnly(enabled)

    /** „Alle Kopplungen aufheben“, nur über das Gerätemenü. */
    fun resetAllPairings() = admin.resetAll()

    /** Nach einem Netzwerkwechsel, damit der Server auf der neuen Adresse annimmt. */
    fun onNetworkChanged() {
        if (!settings.localOnly.value) server.restart()
    }

    /** Hält den Server an und schreibt ausstehende Änderungen. */
    suspend fun stop() {
        server.stop()
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        cache.close()
        settings.flush()
        clients.flush()
        index.flush()
        scope.cancel()
    }

    private fun registerRoutes() {
        router.infoRoutes(identity, settings, platform)
        router.pairingRoutes(pairing, clients)
        router.settingsRoutes(settings)
        router.libraryRoutes(library, settings, signer)
        router.diagnosticsRoutes(log)
    }

    private suspend fun scanLoop() {
        while (scope.isActive) {
            try {
                localSource.scan()
            } catch (e: Exception) {
                log.e(TAG, "Scan fehlgeschlagen", e)
            }
            withTimeoutOrNull(scanInterval.toMillis()) { scanSignal.receive() }
        }
    }

    companion object {
        const val DEFAULT_PORT = 8080
        private const val HANDLER_THREADS = 8
        private const val TAG = "core"
    }
}
