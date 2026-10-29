package io.github.tycho991.openframe.core

import io.github.tycho991.openframe.core.api.diagnosticsRoutes
import io.github.tycho991.openframe.core.api.displayRoutes
import io.github.tycho991.openframe.core.api.infoRoutes
import io.github.tycho991.openframe.core.api.libraryRoutes
import io.github.tycho991.openframe.core.api.pairingRoutes
import io.github.tycho991.openframe.core.api.settingsRoutes
import io.github.tycho991.openframe.core.api.sourcesRoutes
import io.github.tycho991.openframe.core.auth.AccessControl
import io.github.tycho991.openframe.core.auth.ClientRegistry
import io.github.tycho991.openframe.core.auth.ImageSigner
import io.github.tycho991.openframe.core.auth.PairingAdmin
import io.github.tycho991.openframe.core.auth.PairingManager
import io.github.tycho991.openframe.core.auth.StoredClients
import io.github.tycho991.openframe.core.auth.StoredSecret
import io.github.tycho991.openframe.core.display.DisplayController
import io.github.tycho991.openframe.core.http.FrameServer
import io.github.tycho991.openframe.core.http.HttpPipeline
import io.github.tycho991.openframe.core.http.Router
import io.github.tycho991.openframe.core.http.StaticAssets
import io.github.tycho991.openframe.core.identity.FrameIdentity
import io.github.tycho991.openframe.core.library.LocalPhotoSource
import io.github.tycho991.openframe.core.library.MetadataBackfill
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.PhotoIndexStore
import io.github.tycho991.openframe.core.library.PhotoLibrary
import io.github.tycho991.openframe.core.library.RenderCache
import io.github.tycho991.openframe.core.library.RenderSizes
import io.github.tycho991.openframe.core.library.StoredIndex
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.log.LogSink
import io.github.tycho991.openframe.core.log.RotatingFileLog
import io.github.tycho991.openframe.core.nextcloud.NextcloudAccountKind
import io.github.tycho991.openframe.core.nextcloud.NextcloudClients
import io.github.tycho991.openframe.core.nextcloud.NextcloudConnector
import io.github.tycho991.openframe.core.nextcloud.NextcloudMigration
import io.github.tycho991.openframe.core.nextcloud.NextcloudShareKind
import io.github.tycho991.openframe.core.nextcloud.OkHttpNextcloudClients
import io.github.tycho991.openframe.core.places.PlaceIndex
import io.github.tycho991.openframe.core.platform.AssetSource
import io.github.tycho991.openframe.core.platform.Clock
import io.github.tycho991.openframe.core.platform.DisplayPower
import io.github.tycho991.openframe.core.platform.MediaEngine
import io.github.tycho991.openframe.core.platform.MediaStoreNotifier
import io.github.tycho991.openframe.core.platform.NetworkLink
import io.github.tycho991.openframe.core.platform.PlatformInfo
import io.github.tycho991.openframe.core.settings.SettingsRepository
import io.github.tycho991.openframe.core.settings.StoredSettings
import io.github.tycho991.openframe.core.slideshow.CachedDisplayImages
import io.github.tycho991.openframe.core.slideshow.CaptionText
import io.github.tycho991.openframe.core.slideshow.ShownCaption
import io.github.tycho991.openframe.core.slideshow.SlideState
import io.github.tycho991.openframe.core.slideshow.SlideshowController
import io.github.tycho991.openframe.core.slideshow.StoredTransitionGovernor
import io.github.tycho991.openframe.core.slideshow.TransitionGovernor
import io.github.tycho991.openframe.core.sources.SourceConfigStore
import io.github.tycho991.openframe.core.sources.SourceOriginals
import io.github.tycho991.openframe.core.sources.SourceRegistry
import io.github.tycho991.openframe.core.sources.StoredSources
import io.github.tycho991.openframe.core.sources.StoredSyncResults
import io.github.tycho991.openframe.core.sources.SyncResultStore
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
import kotlinx.coroutines.flow.combine
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
    /** Where the Nextcloud sources get their connections from; the real ones unless a test hands in fakes. */
    nextcloudClients: NextcloudClients? = null,
    /** Switching the display off and on; a device without it never switches the display (spec `display-schedule`). */
    displayPower: DisplayPower = DisplayPower.None,
    /** The network, which the frame asks to come back while the display is off; a device without it is always connected. */
    networkLink: NetworkLink = NetworkLink.None,
    /**
     * Binds the server to loopback whatever the setting "local only" says, which itself stays as it is. Only for the
     * test server and tests, so that a test frame is not reachable from the LAN; the frame never sets it.
     */
    private val bindLoopback: Boolean = false,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lanAddressCache: Pair<Long, String?>? = null

    val log = AppLog(clock, RotatingFileLog(File(dirs.state, "logs")), sinks = logSinks)

    val settings =
        SettingsRepository(
            JsonStore(File(dirs.state, "settings.json"), StoredSettings.serializer(), scope, log),
            platform.model,
            log,
        ) { displayPower.available }

    /** The display schedule, the manual switch and waking. The display reports screen events and touches here. */
    val display = DisplayController(scope, displayPower, settings.settings, clock, log, networkLink)

    val transitions =
        TransitionGovernor(JsonStore(File(dirs.state, "transitions.json"), StoredTransitionGovernor.serializer(), scope, log), log)

    val clients = ClientRegistry(JsonStore(File(dirs.state, "clients.json"), StoredClients.serializer(), scope, log), clock)
    val signer = ImageSigner(JsonStore(File(dirs.state, "secret.json"), StoredSecret.serializer(), scope, log), clock)
    val pairing = PairingManager(clients, clock, log, ::adminUrl)
    private val admin = PairingAdmin(clients, signer, pairing, log)

    private val identityStore = JsonStore(File(dirs.state, "identity.json"), FrameIdentity.serializer(), scope, log)
    private val index = PhotoIndexStore(JsonStore(File(dirs.state, "index.json"), StoredIndex.serializer(), scope, log))
    val localSource = LocalPhotoSource(dirs.photos, engine, index, log)
    private val sourceConfigs = SourceConfigStore(JsonStore(File(dirs.state, "sources.json"), StoredSources.serializer(), scope, log))
    private val syncResults =
        SyncResultStore(JsonStore(File(dirs.state, "sources-status.json"), StoredSyncResults.serializer(), scope, log))
    private val nextcloudClients = nextcloudClients ?: OkHttpNextcloudClients()
    private val migration = NextcloudMigration(dirs.state, dirs.cache, sourceConfigs, syncResults, log)

    /** The remote sources: settings, status, sync on request, removal. */
    val sources =
        SourceRegistry(
            scope,
            sourceConfigs,
            syncResults,
            listOf(NextcloudAccountKind(this.nextcloudClients), NextcloudShareKind(this.nextcloudClients)),
            index,
            engine,
            File(dirs.cache, "sources"),
            { cache.remove(it.id) },
            clock,
            log,
            migration::sourceRemoved,
            onSynced = { backfill.request() },
        )

    /** Adding Nextcloud sources: the login flow for an account, the link of a share. */
    val nextcloud = NextcloudConnector(sources, this.nextcloudClients, clock)
    val cache: RenderCache =
        RenderCache(
            File(dirs.cache, "renders"),
            engine,
            SourceOriginals(localSource, sources)::fileOf,
            { RenderSizes.screen(platform.screenWidth, platform.screenHeight, settings.settings.value.orientation) },
            { settings.settings.value.fillMode },
            { settings.settings.value.cacheBudgetMb * 1024L * 1024L },
            log,
        )
    val library = PhotoLibrary(index, localSource, cache, engine, notifier, platform, log)

    /** Place names of photo positions, offline; loads its data on the first lookup. */
    val places = PlaceIndex(log)

    /** Reads the GPS position of photos from before it was read, once, after start, scans with changes and syncs. */
    private val backfill: MetadataBackfill = MetadataBackfill(index, engine, SourceOriginals(localSource, sources)::onFrame, log)

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

    /**
     * The caption of the photo on screen, `null` while none is shown. It follows the photo's current entry, the
     * settings and the language, so an edited comment or a switched-on place shows without a photo change.
     */
    val caption: StateFlow<ShownCaption?> =
        combine(slideshow.state, index.library, settings.settings) { state, library, s ->
            val showing = state as? SlideState.Showing ?: return@combine null
            val entry = library.photos[showing.photo.id] ?: showing.photo
            // The place data loads only for a caption that shows a place.
            val place =
                if (s.caption.place) entry.gps?.let { places.placeName(it.lat, it.lon, s.language.locale(platform)) } else null
            ShownCaption(showing.seq, CaptionText.of(entry, s.caption, place))
        }.distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null)

    val router = Router()
    private val pipeline = HttpPipeline(router, AccessControl(clients, signer), log, StaticAssets(assets)::handle)
    val server = FrameServer(port, Dispatchers.IO.limitedParallelism(HANDLER_THREADS), log, pipeline::handle)

    private val scanSignal = Channel<Unit>(Channel.CONFLATED)

    /** Loads everything, starts the background work and the server. */
    suspend fun start() {
        log.i(TAG, "Start: ${platform.model}, Android ${platform.androidVersion}, App ${platform.appVersion}")
        settings.load()
        transitions.load()
        clients.load()
        signer.load()
        index.load()
        syncResults.load()
        // Only a frame that never had a sources.json migrates; one that could not be read must not become 0.1.0 again.
        if (sourceConfigs.load() == SourceConfigStore.Loaded.MISSING) migration.migrate()
        identity = FrameIdentity.loadOrCreate(identityStore)
        registerRoutes()
        slideshow.start()
        display.start()
        // It follows the real screen, so the slideshow also rests while the display is off by the power button.
        scope.launch {
            display.state
                .map { !it.on }
                .distinctUntilChanged()
                .collect(slideshow::setResting)
        }
        scope.launch { settings.settings.collect(transitions::onSettings) }
        sources.start()
        _ready.value = true
        backfill.request()

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
            settings.localOnly.drop(1).collect { server.restart(bindLoopback || it) }
        }
        server.start(bindLoopback || settings.localOnly.value)
    }

    /**
     * Address of the admin interface as the frame shows it, e.g. `http://192.168.100.215:8080/`:
     * first LAN address and bound port, without port for port 80. `null` without a network address.
     */
    fun adminUrl(): String? {
        val ip = lanAddress() ?: return null
        val port = server.boundPort ?: port
        return if (port == 80) "http://$ip/" else "http://$ip:$port/"
    }

    /**
     * The first LAN address. Listing the network interfaces is not cheap and the pairing display asks
     * every second (and the display on the UI thread), so the answer is kept for a few seconds.
     */
    private fun lanAddress(): String? {
        val now = clock.millis()
        lanAddressCache?.let { (at, ip) -> if (now - at in 0 until LAN_ADDRESS_TTL_MS) return ip }
        val ip = platform.ipAddresses().firstOrNull()
        lanAddressCache = now to ip
        return ip
    }

    /** Scans the photo folder immediately instead of at the next interval. */
    fun requestScan() {
        scanSignal.trySend(Unit)
    }

    /** "Local only" mode, only through the device menu. */
    fun setLocalOnly(enabled: Boolean) = settings.setLocalOnly(enabled)

    /**
     * "Revoke all pairings", only through the device menu. The tokens are replaced and written
     * with `fsync`, which must not run on the UI thread, so this runs on the core's scope.
     */
    fun resetAllPairings(): kotlinx.coroutines.Job = scope.launch { admin.resetAll() }

    /** After a network change, so that the server accepts on the new address. */
    fun onNetworkChanged() {
        lanAddressCache = null
        sources.networkChanged()
        if (!settings.localOnly.value) server.restart()
    }

    /** Stops the server and writes pending changes. */
    suspend fun stop() {
        server.stop()
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        cache.close()
        backfill.close()
        settings.flush()
        clients.flush()
        index.flush()
        sourceConfigs.flush()
        syncResults.flush()
        scope.cancel()
    }

    private fun registerRoutes() {
        router.infoRoutes(identity, settings, platform)
        router.pairingRoutes(pairing, clients, ::adminUrl)
        router.settingsRoutes(settings)
        router.libraryRoutes(library, settings, signer, transitions, places, platform)
        router.sourcesRoutes(sources, nextcloud)
        router.displayRoutes(display)
        router.diagnosticsRoutes(log)
    }

    private suspend fun scanLoop() {
        while (scope.isActive) {
            try {
                backfill.scanned(localSource.scan())
            } catch (e: Exception) {
                log.e(TAG, "Scan failed", e)
            }
            withTimeoutOrNull(scanInterval.toMillis()) { scanSignal.receive() }
        }
    }

    companion object {
        const val DEFAULT_PORT = 8080
        private const val HANDLER_THREADS = 8
        private const val LAN_ADDRESS_TTL_MS = 5_000L
        private const val TAG = "core"
    }
}
