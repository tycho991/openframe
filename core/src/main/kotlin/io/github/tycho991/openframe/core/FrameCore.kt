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

/** The frame's folders: state, cache and the photo folder reachable over USB. */
data class FrameDirs(
    val state: File,
    val cache: File,
    val photos: File,
)

/**
 * The frame's core. Loads settings and library, runs the
 * HTTP server and delivers the display its data as [StateFlow]s, without HTTP.
 */
class FrameCore(
    private val dirs: FrameDirs,
    val platform: PlatformInfo,
    engine: MediaEngine,
    assets: AssetSource,
    notifier: MediaStoreNotifier,
    val clock: Clock = Clock.System,
    private val port: Int = DEFAULT_PORT,
    logSinks: List<LogSink> = emptyList(),
    private val scanInterval: Duration = Duration.ofSeconds(15),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val log = AppLog(clock, RotatingFileLog(File(dirs.state, "logs")), sinks = logSinks)

    val settings =
        SettingsRepository(JsonStore(File(dirs.state, "settings.json"), StoredSettings.serializer(), scope, log), platform.model, log)

    val clients = ClientRegistry(JsonStore(File(dirs.state, "clients.json"), StoredClients.serializer(), scope, log), clock)
    val signer = ImageSigner(JsonStore(File(dirs.state, "secret.json"), StoredSecret.serializer(), scope, log), clock)
    val pairing = PairingManager(clients, clock, log, ::adminUrl)
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

    /** Settings and library are loaded. The display waits only for this, not for server or network. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** The photos visible to the slideshow, by capture date. */
    val visiblePhotos: StateFlow<List<PhotoEntry>> =
        index.library
            .map { it.visible }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Slideshow control for the display. */
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

    /** Loads everything, starts the background work and the server. */
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

    /**
     * Address of the admin interface as the frame shows it, e.g. `http://192.168.100.215:8080/`:
     * first LAN address and bound port, without port for port 80. `null` without a network address.
     */
    fun adminUrl(): String? {
        val ip = platform.ipAddresses().firstOrNull() ?: return null
        val port = server.boundPort ?: port
        return if (port == 80) "http://$ip/" else "http://$ip:$port/"
    }

    /** Scans the photo folder immediately instead of at the next interval. */
    fun requestScan() {
        scanSignal.trySend(Unit)
    }

    /** "Local only" mode, only through the device menu. */
    fun setLocalOnly(enabled: Boolean) = settings.setLocalOnly(enabled)

    /** "Revoke all pairings", only through the device menu. */
    fun resetAllPairings() = admin.resetAll()

    /** After a network change, so that the server accepts on the new address. */
    fun onNetworkChanged() {
        if (!settings.localOnly.value) server.restart()
    }

    /** Stops the server and writes pending changes. */
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
        router.pairingRoutes(pairing, clients, ::adminUrl)
        router.settingsRoutes(settings)
        router.libraryRoutes(library, settings, signer)
        router.diagnosticsRoutes(log)
    }

    private suspend fun scanLoop() {
        while (scope.isActive) {
            try {
                localSource.scan()
            } catch (e: Exception) {
                log.e(TAG, "Scan failed", e)
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
