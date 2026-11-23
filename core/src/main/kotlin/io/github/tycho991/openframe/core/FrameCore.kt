package io.github.tycho991.openframe.core

import io.github.tycho991.openframe.core.api.PhotoDtos
import io.github.tycho991.openframe.core.api.diagnosticsRoutes
import io.github.tycho991.openframe.core.api.displayRoutes
import io.github.tycho991.openframe.core.api.healthRoutes
import io.github.tycho991.openframe.core.api.infoRoutes
import io.github.tycho991.openframe.core.api.libraryRoutes
import io.github.tycho991.openframe.core.api.pairingRoutes
import io.github.tycho991.openframe.core.api.settingsRoutes
import io.github.tycho991.openframe.core.api.slideshowRoutes
import io.github.tycho991.openframe.core.api.sourcesRoutes
import io.github.tycho991.openframe.core.api.trashRoutes
import io.github.tycho991.openframe.core.api.updateRoutes
import io.github.tycho991.openframe.core.auth.AccessControl
import io.github.tycho991.openframe.core.auth.ClientRegistry
import io.github.tycho991.openframe.core.auth.ImageSigner
import io.github.tycho991.openframe.core.auth.PairingAdmin
import io.github.tycho991.openframe.core.auth.PairingManager
import io.github.tycho991.openframe.core.auth.StoredClients
import io.github.tycho991.openframe.core.auth.StoredSecret
import io.github.tycho991.openframe.core.display.DisplayController
import io.github.tycho991.openframe.core.display.StoredBrightnessSnapshot
import io.github.tycho991.openframe.core.health.Health
import io.github.tycho991.openframe.core.health.RunEnd
import io.github.tycho991.openframe.core.health.RunJournal
import io.github.tycho991.openframe.core.http.FrameServer
import io.github.tycho991.openframe.core.http.HttpPipeline
import io.github.tycho991.openframe.core.http.LocalAddressFilter
import io.github.tycho991.openframe.core.http.Router
import io.github.tycho991.openframe.core.http.ServerState
import io.github.tycho991.openframe.core.http.StaticAssets
import io.github.tycho991.openframe.core.identity.FrameIdentity
import io.github.tycho991.openframe.core.library.CaptureDates
import io.github.tycho991.openframe.core.library.LocalPhotoSource
import io.github.tycho991.openframe.core.library.MetadataBackfill
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.PhotoIndexStore
import io.github.tycho991.openframe.core.library.PhotoLibrary
import io.github.tycho991.openframe.core.library.RenderCache
import io.github.tycho991.openframe.core.library.RenderSizes
import io.github.tycho991.openframe.core.library.StorageReserve
import io.github.tycho991.openframe.core.library.StoredIndex
import io.github.tycho991.openframe.core.library.StoredTrash
import io.github.tycho991.openframe.core.library.TrashItem
import io.github.tycho991.openframe.core.library.TrashStore
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
import io.github.tycho991.openframe.core.platform.AppInstaller
import io.github.tycho991.openframe.core.platform.AssetSource
import io.github.tycho991.openframe.core.platform.Clock
import io.github.tycho991.openframe.core.platform.DisplayPower
import io.github.tycho991.openframe.core.platform.MediaEngine
import io.github.tycho991.openframe.core.platform.MediaStoreNotifier
import io.github.tycho991.openframe.core.platform.NetworkLink
import io.github.tycho991.openframe.core.platform.PlatformInfo
import io.github.tycho991.openframe.core.platform.ProcessControl
import io.github.tycho991.openframe.core.platform.ScreenBrightness
import io.github.tycho991.openframe.core.settings.BrightnessSupport
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
import io.github.tycho991.openframe.core.update.GithubReleases
import io.github.tycho991.openframe.core.update.ReleaseSource
import io.github.tycho991.openframe.core.update.StoredUpdate
import io.github.tycho991.openframe.core.update.UpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.Duration

/**
 * The frame's folders: state, cache, the photo folder reachable over USB, and the folder of a downloaded update, which
 * is no cache, so that Android does not clear it and a ready update survives restarts.
 */
data class FrameDirs(
    val state: File,
    val cache: File,
    val photos: File,
    val updates: File = File(state.absoluteFile.parentFile, "update"),
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
    /** How often the photo folder is scanned while the display is off (design decision 7 of `library-performance`). */
    private val nightScanInterval: Duration = Duration.ofMinutes(5),
    /** Where the Nextcloud sources get their connections from; the real ones unless a test hands in fakes. */
    nextcloudClients: NextcloudClients? = null,
    /** Switching the display off and on; a device without it never switches the display (spec `display-schedule`). */
    displayPower: DisplayPower = DisplayPower.None,
    /** The network, which the frame asks to come back while the display is off; a device without it is always connected. */
    networkLink: NetworkLink = NetworkLink.None,
    /** Android's brightness; a device without it never changes the brightness (spec `display-brightness`). */
    screenBrightness: ScreenBrightness = ScreenBrightness.None,
    /**
     * Binds the server to loopback whatever the setting "local only" says, which itself stays as it is. Only for the
     * test server and tests, so that a test frame is not reachable from the LAN; the frame never sets it.
     */
    private val bindLoopback: Boolean = false,
    /** The first wait before binding the port again after a failure; it doubles up to [SERVER_RETRY_MAX_MS]. */
    private val serverRetryMs: Long = SERVER_RETRY_FIRST_MS,
    /** What the run journal decided at the start of this process; in safe mode photos, sources and image work rest. */
    val health: Health = Health.normal(),
    /** Restarts the app to leave safe mode. */
    private val processControl: ProcessControl = ProcessControl.None,
    /** How long a run in normal mode lasts before it counts as stable. */
    private val stableAfterMs: Long = RunJournal.STABLE_AFTER_MS,
    /** Installing new versions of the app; a device without it never looks for updates (spec `app-update`). */
    installer: AppInstaller = AppInstaller.None,
    /** Where updates come from: GitHub unless a test or the test server hands in a fake. */
    releases: ReleaseSource? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lanAddressCache: Pair<Long, String?>? = null

    val log = AppLog(clock, RotatingFileLog(File(dirs.state, "logs")), sinks = logSinks)

    val settings =
        SettingsRepository(
            JsonStore(File(dirs.state, "settings.json"), StoredSettings.serializer(), scope, log, backupOnLoad = true),
            platform.model,
            log,
            brightnessSupport = { BrightnessSupport(screenBrightness.available, screenBrightness.autoSupported) },
        ) { displayPower.available }

    /**
     * The display schedule, the manual switch, waking and the brightness. The display reports screen events and touches
     * here.
     */
    val display =
        DisplayController(
            scope,
            displayPower,
            settings.settings,
            clock,
            log,
            networkLink,
            screenBrightness,
            JsonStore(File(dirs.state, "brightness-restore.json"), StoredBrightnessSnapshot.serializer(), scope, log),
        ) { level ->
            settings.patch(
                buildJsonObject {
                    putJsonObject("brightness") {
                        put("mode", "manual")
                        put("level", level)
                    }
                },
            )
        }

    val transitions =
        TransitionGovernor(
            JsonStore(File(dirs.state, "transitions.json"), StoredTransitionGovernor.serializer(), scope, log, backupOnLoad = true),
            log,
        )

    val clients = ClientRegistry(JsonStore(File(dirs.state, "clients.json"), StoredClients.serializer(), scope, log), clock)
    val signer = ImageSigner(JsonStore(File(dirs.state, "secret.json"), StoredSecret.serializer(), scope, log), clock)
    val pairing = PairingManager(clients, clock, log, ::adminUrl)
    private val admin = PairingAdmin(clients, signer, pairing, log)

    /** The storage reserve of the data volume, computed once from the cache folder (design decision 1 of `library-performance`). */
    private val volumeBytes = platform.totalBytes(dirs.cache.apply { mkdirs() })
    val reserveBytes = StorageReserve.bytes(volumeBytes)

    private val identityStore = JsonStore(File(dirs.state, "identity.json"), FrameIdentity.serializer(), scope, log, backupOnLoad = true)
    private val index =
        PhotoIndexStore(
            JsonStore(File(dirs.state, "index.json"), StoredIndex.serializer(), scope, log, backupOnLoad = true),
            log,
            ::photosRemoved,
        )
    val localSource = LocalPhotoSource(dirs.photos, engine, index, log, platform::storageMounted, clock = clock)

    /** Deleted local photos, for 7 days (spec `photo-library`, "Trash for deleted photos"). */
    val trash =
        TrashStore(
            dirs.photos,
            JsonStore(File(dirs.state, "trash.json"), StoredTrash.serializer(), scope, log, backupOnLoad = true),
            clock,
            log,
            localSource::available,
            platform::freeBytes,
            platform::totalBytes,
            removeRenderings = { cache.removeAll(it) },
        )
    private val sourceConfigs = SourceConfigStore(JsonStore(File(dirs.state, "sources.json"), StoredSources.serializer(), scope, log))
    private val syncResults =
        SyncResultStore(JsonStore(File(dirs.state, "sources-status.json"), StoredSyncResults.serializer(), scope, log))
    private val nextcloudClients = nextcloudClients ?: OkHttpNextcloudClients(clock)
    private val migration: NextcloudMigration =
        NextcloudMigration(dirs.state, dirs.cache, sourceConfigs, syncResults, log) { old ->
            sources.revokeLater("The app password of version 0.1.0 for ${SourceConfigStore.hostOf(old.serverUrl)}") {
                this.nextcloudClients.loginFlow().revoke(old.serverUrl, old.loginName, old.appPassword)
            }
        }

    /** The remote sources: settings, status, sync on request, removal. */
    val sources: SourceRegistry =
        SourceRegistry(
            scope,
            sourceConfigs,
            syncResults,
            listOf(NextcloudAccountKind(this.nextcloudClients), NextcloudShareKind(this.nextcloudClients)),
            index,
            engine,
            File(dirs.cache, "sources"),
            clock,
            log,
            migration::sourceRemoved,
            freeBytes = platform::freeBytes,
            onSynced = { backfill.request() },
            reserveBytes = reserveBytes,
            makeRoom = { dir, bytes -> trash.makeRoom(dir, bytes) },
        )

    /** Adding Nextcloud sources: the login flow for an account, the link of a share. */
    val nextcloud = NextcloudConnector(sources, this.nextcloudClients, clock)
    private val originals = SourceOriginals(localSource, sources, trash)
    val cache: RenderCache =
        RenderCache(
            File(dirs.cache, RenderCache.FOLDER),
            engine,
            { originals.onFrame(it) },
            { RenderSizes.screen(platform.screenWidth, platform.screenHeight, settings.settings.value.orientation) },
            { settings.settings.value.fillMode },
            { settings.settings.value.cacheBudgetMb * 1024L * 1024L },
            log,
            onCrashed = ::quarantine,
            fetch = originals::fileOf,
            release = originals::release,
            freeBytes = platform::freeBytes,
            reserveBytes = reserveBytes,
            clock = clock,
            makeRoom = { dir, bytes -> trash.makeRoom(dir, bytes) },
        )
    val library = PhotoLibrary(index, localSource, cache, engine, notifier, platform, log, trash, reserveBytes)

    /** Updates of the app from GitHub's releases (spec `app-update`). Android reports installation results here. */
    val update =
        UpdateManager(
            scope,
            JsonStore(File(dirs.state, "update.json"), StoredUpdate.serializer(), scope, log),
            releases ?: GithubReleases(),
            installer,
            platform,
            health,
            settings.settings,
            dirs.updates,
            clock,
            log,
            makeRoom = { dir, bytes -> trash.makeRoom(dir, bytes) },
        )

    /** Place names of photo positions, offline; loads its data on the first lookup. */
    val places = PlaceIndex(log)

    /** Reads the GPS position of photos from before it was read, once, after start, scans with changes and syncs. */
    private val backfill: MetadataBackfill = MetadataBackfill(index, engine, { originals.onFrame(it, markUsed = false) }, log)

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
    private val localAddresses = LocalAddressFilter(clock, platform::localAddresses)
    val server =
        FrameServer(
            port,
            Dispatchers.IO.limitedParallelism(HANDLER_THREADS),
            log,
            pipeline::handle,
            acceptsLocal = localAddresses::accepts,
        )

    private val scanSignal = Channel<Unit>(Channel.CONFLATED)
    private val bindSignal = Channel<Unit>(Channel.CONFLATED)
    private var bindJob: Job? = null

    /** Loads everything, starts the background work and the server. */
    suspend fun start() {
        val build = if (platform.appDebuggable) "debug" else "release"
        log.i(
            TAG,
            "Start: ${platform.model}, Android ${platform.androidVersion}, App ${platform.appVersion} (${platform.appVersionCode ?: "?"}, $build)",
        )
        logHealth()
        log.i(TAG, "Storage reserve: ${reserveBytes / StorageReserve.MIB} MiB of ${volumeBytes / StorageReserve.MIB} MiB")
        settings.load()
        transitions.load()
        clients.load()
        signer.load()
        index.load()
        trash.load()
        syncResults.load()
        // Only a frame that never had a sources.json migrates; one that could not be read must not become 0.1.0 again.
        val loadedSources = sourceConfigs.load()
        val migrated = loadedSources == SourceConfigStore.Loaded.MISSING && migration.migrate()
        identity = FrameIdentity.loadOrCreate(identityStore)
        library.removePartialUploads()
        correctCaptureDates()
        // Renderings of photos that are not in the index, and of versions it will assign again: files from before the
        // update, a deletion the end of the process cut off, an index restored from its backup (design decision 4 of
        // `library-performance`). Before the slideshow asks for any.
        val swept =
            cache.sweep { id ->
                index.library.value.photos[id]
                    ?.version
            }
        if (swept > 0) log.i(TAG, "$swept renderings of photos that are not in the index removed")
        // Renderings of an older format, after an update; on the core's scope, there may be thousands of files.
        scope.launch {
            RenderCache.staleFolders(dirs.cache).forEach { stale ->
                log.i(TAG, "Renderings of an older format deleted (${stale.name})")
                stale.deleteRecursively()
            }
        }
        registerRoutes()
        val safe = health.safe
        // Safe mode (spec `kiosk-host`, "Safe mode after repeated crashes"): no slideshow, scan, sync, backfill or
        // prefetch, so that nothing decodes; server, pairing, settings, display schedule and log run.
        if (!safe) slideshow.start()
        display.start()
        // It follows the real screen, so the slideshow also rests while the display is off by the power button.
        scope.launch {
            display.state
                .map { !it.on }
                .distinctUntilChanged()
                .collect { resting ->
                    slideshow.setResting(resting)
                    // While the display was off the folder was scanned less often (spec "Local scan while the display is off").
                    if (!resting && wasResting) requestScan()
                    wasResting = resting
                }
        }
        // A pairing code only while someone can see it: the display is on and not in a dark wake (design decision 5
        // of harden-lan-server).
        scope.launch {
            combine(display.state, display.dark) { state, dark -> state.on && !dark }
                .distinctUntilChanged()
                .collect(pairing::setScreenUsable)
        }
        scope.launch { settings.settings.collect(transitions::onSettings) }
        // While the installation dialog is open, the display is on and stays on (design decision 8 of app-auto-update).
        scope.launch {
            update.state
                .map { it.installUntilMono }
                .distinctUntilChanged()
                .drop(1)
                .collect { until ->
                    if (until != null) {
                        display.wake()
                        display.hold(until, "the installation dialog")
                    } else {
                        display.release()
                    }
                }
        }
        update.start()
        sources.start(removeOrphans = loadedSources != SourceConfigStore.Loaded.UNREADABLE, launch = !safe)
        // Not with a list of sources that cannot be read, and not in safe mode (design decision 9 of nextcloud-hardening).
        if (!safe && (loadedSources == SourceConfigStore.Loaded.READ || migrated)) migration.cleanUpLeftovers()
        _ready.value = true
        if (!safe) {
            backfill.request()
            scope.launch { scanLoop() }
            scope.launch {
                delay(stableAfterMs)
                health.markStable()
            }
        } else {
            scope.launch { probeLoop() }
        }
        scope.launch {
            while (isActive) {
                delay(UPTIME_RECORD_MS)
                health.recordUptime()
            }
        }
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
        val firstBind = CompletableDeferred<Unit>()
        bindJob = scope.launch { bindLoop(firstBind) }
        scope.launch { settings.localOnly.drop(1).collect { requestBind() } }
        requestBind()
        firstBind.await()
        // After the server started: a large trash on an SD card must not delay it (design decision 3 of
        // `paired-device-guards`). Then the purge check hourly, also in safe mode, since it decodes nothing.
        scope.launch { trashLoop() }
    }

    private suspend fun trashLoop() {
        while (scope.isActive) {
            try {
                trash.ensureBuilt()
                trash.purge()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e(TAG, "The purge check of the trash failed", e)
            }
            delay(TRASH_CHECK_MS)
        }
    }

    private fun logHealth() {
        val h = health.state.value
        val crash = h.previousCrash?.let { " ($it)" } ?: ""
        val bootId = if (h.bootIdReadable) "boot_id readable" else "boot_id not readable"
        val line = "Previous run: ${h.previousEnd.description}$crash; early ends in a row: ${h.recentEarlyEnds}; $bootId"
        if (h.previousEnd == RunEnd.CRASHED || h.previousEnd == RunEnd.DIED) log.w(TAG, line) else log.i(TAG, line)
        h.safeMode?.let { log.w(TAG, "Safe mode: ${it.reason}; photos, sources and image work rest") }
        if (h.probation) log.i(TAG, "First run after safe mode: an early end leads straight back into it")
    }

    /** In safe mode: leaves it by a restart in normal mode 1 h after it began, doubling up to 24 h. */
    private suspend fun probeLoop() {
        val at = health.probeAtMs() ?: return
        delay((at - clock.millis()).coerceIn(0, RunJournal.PROBE_HOURS.last() * RunJournal.HOUR_MS))
        log.i(TAG, "Safe mode: trying normal mode again")
        restartNormally("probe")
    }

    /**
     * Whether the device menu offers Android's settings (spec `kiosk-host`, "Android settings hidden where the firmware
     * ends the app"): not when the firmware is known to end the app when another activity starts, nor when a start
     * taught it. The menu also checks that the device has an activity for them.
     */
    val androidSettingsOffered: Boolean
        get() = !platform.otherActivityEndsApp && !health.state.value.systemScreenEndsApp

    /**
     * Before the device menu opens a system screen for [reason] (spec `kiosk-host`, "System screen from the device
     * menu"): the record that keeps ends under the screen planned, and the display held on for
     * [RunJournal.SYSTEM_SCREEN_VALID_MS] ("Display while a system screen is open").
     */
    fun systemScreenOpened(reason: String) {
        health.systemScreenOpened(reason)
        display.hold(clock.monotonicMillis() + RunJournal.SYSTEM_SCREEN_VALID_MS, "a system screen ($reason)")
    }

    /** The slideshow is back in front, or the system screen did not start: withdraws the record and ends the hold. */
    fun systemScreenClosed() {
        health.cancelSystemScreen()
        display.release()
    }

    /**
     * Leaves safe mode: the next start runs in normal mode (spec `kiosk-host`, "Leaving safe mode"). `false` if the
     * frame is not in safe mode. The restart comes about a second later, so that an API answer gets out first.
     */
    fun leaveSafeMode(reason: String): Boolean {
        if (!health.safe) return false
        scope.launch {
            delay(RESTART_DELAY_MS)
            restartNormally(reason)
        }
        return true
    }

    private fun restartNormally(reason: String) {
        health.requestNormalStart()
        processControl.restart(reason)
    }

    /** Asks the one rebind path to bind the server anew. Several requests in a row lead to one bind. */
    private fun requestBind() {
        bindSignal.trySend(Unit)
    }

    /**
     * The only place that binds the server, one request after the other, so that start, local-only changes, network
     * changes and retries do not race and nothing binds on the main thread. While binding fails, it tries again after
     * [serverRetryMs], doubling up to [SERVER_RETRY_MAX_MS] (spec `kiosk-host`, "Server port unavailable").
     */
    private suspend fun bindLoop(firstBind: CompletableDeferred<Unit>) {
        var failures = 0
        while (true) {
            if (failures == 0) {
                bindSignal.receive()
            } else {
                val wait = minOf(serverRetryMs shl minOf(failures - 1, MAX_RETRY_DOUBLINGS), SERVER_RETRY_MAX_MS)
                withTimeoutOrNull(wait) { bindSignal.receive() }
            }
            server.start(bindLoopback || settings.localOnly.value)
            failures = if (server.state.value is ServerState.Failed) failures + 1 else 0
            firstBind.complete(Unit)
        }
    }

    /**
     * Address of the admin interface as the frame shows it, e.g. `http://192.168.100.215:8080/`:
     * first LAN address and bound port, without port for port 80. `null` without a network address and while the
     * server is not bound; [FrameServer.state] tells the two apart.
     */
    fun adminUrl(): String? {
        val port = (server.state.value as? ServerState.Running)?.port ?: return null
        return adminUrl(lanAddress() ?: return null, port)
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

    /** The display was off at its last change; see [start]. */
    private var wasResting = false

    /** When the last scan began or was asked for by a client, on [Clock.monotonicMillis]. */
    @Volatile private var lastScanMono = Long.MIN_VALUE / 2

    /** Scans that began, for the tests of the cadence. */
    internal val scans =
        java.util.concurrent.atomic
            .AtomicInteger()

    /**
     * A client fetches photos, albums or skipped files: a scan now unless one began less than [CLIENT_SCAN_MS] ago, so
     * that a file copied over USB at night shows at the client's next fetch. The request does not wait for it.
     */
    private fun scanForClient() {
        val now = clock.monotonicMillis()
        synchronized(scanSignal) {
            if (now - lastScanMono <= CLIENT_SCAN_MS) return
            lastScanMono = now
        }
        requestScan()
    }

    /** "Local only" mode, only through the device menu. */
    fun setLocalOnly(enabled: Boolean) = settings.setLocalOnly(enabled)

    /**
     * "Revoke all pairings", only through the device menu. The tokens are replaced and written
     * with `fsync`, which must not run on the UI thread, so this runs on the core's scope.
     */
    fun resetAllPairings(): kotlinx.coroutines.Job =
        scope.launch {
            try {
                admin.resetAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.e(TAG, "Revoking all pairings failed", e)
            }
        }

    /**
     * Photos left the index, by any path (design decision 4 of `library-performance`): their renderings and the
     * originals of remote ones go. Inside the index's lock; both only change memory and delete in the background.
     */
    private fun photosRemoved(removed: List<PhotoEntry>) {
        cache.removeAll(removed)
        sources.photosRemoved(removed)
    }

    /**
     * Entries with a capture date before 1800, from before such dates counted as missing, get their file date (spec
     * `photo-library`, "Implausible capture dates"), in one edit.
     */
    private fun correctCaptureDates() {
        val corrected =
            index.edit { edit ->
                edit
                    .all()
                    .filter { CaptureDates.implausible(it.takenAtMs) }
                    .onEach { edit.put(it.copy(takenAtMs = it.modifiedMs)) }
                    .size
            }
        if (corrected > 0) log.i(TAG, "$corrected implausible capture dates replaced by the file date")
    }

    /**
     * Quarantines the version of [entry] whose rendering crashed the decoder, unless the file changed meanwhile; for a
     * trash thumbnail its trash record (design decision 10 of `paired-device-guards`). Outside the cache's lock.
     */
    private fun quarantine(entry: PhotoEntry) {
        if (entry.source == TrashItem.SOURCE) {
            trash.quarantine(entry)
            return
        }
        index.edit { edit ->
            val current = edit.get(entry.id) ?: return@edit
            if (current.version == entry.version) edit.put(current.copy(quarantinedVersion = entry.version))
        }
    }

    /** After a network change, so that the server accepts on the new address. */
    fun onNetworkChanged() {
        lanAddressCache = null
        localAddresses.clear()
        if (!health.safe) sources.networkChanged()
        update.networkChanged()
        // Bound to loopback, local-only mode does not care about the network, unless it could not bind at all.
        if (!settings.localOnly.value || server.state.value is ServerState.Failed) requestBind()
    }

    /** Stops the server, waits for the background work and writes pending changes. */
    suspend fun stop() {
        bindJob?.cancelAndJoin()
        server.stop()
        // Cancelling alone returns while a scan is still in blocking I/O; it would log into the state folder after
        // stop() returned. The Android app never stops the core, the tests do with every TestFrame.
        val jobs =
            scope.coroutineContext[kotlinx.coroutines.Job]
                ?.children
                ?.toList()
                .orEmpty()
        jobs.forEach { it.cancel() }
        if (withTimeoutOrNull(STOP_WAIT_MS) { jobs.joinAll() } == null) {
            log.w(TAG, "Background work still running ${STOP_WAIT_MS / 1000} s after stop")
        }
        cache.close()
        backfill.close()
        settings.flush()
        clients.flush()
        index.flush()
        trash.flush()
        sourceConfigs.flush()
        syncResults.flush()
        update.flush()
        scope.cancel()
    }

    private fun registerRoutes() {
        router.infoRoutes(identity, settings, platform)
        router.pairingRoutes(pairing, clients, log, ::adminUrl)
        router.settingsRoutes(settings)
        val photos = PhotoDtos(library, settings, signer, places, platform)
        router.libraryRoutes(library, settings, photos, transitions, platform, health::safe) { if (!health.safe) scanForClient() }
        router.trashRoutes(library, photos, clock, health::safe)
        router.sourcesRoutes(sources, nextcloud, health::safe)
        router.healthRoutes(::leaveSafeMode)
        router.displayRoutes(display)
        router.slideshowRoutes(slideshow, photos, health::safe)
        router.updateRoutes(update, clients)
        router.diagnosticsRoutes(log)
    }

    /**
     * Scans the photo folder every [scanInterval] while the display is on and every [nightScanInterval] while it is off,
     * and at once on [requestScan] (spec `photo-library`, "Local scan while the display is off"). The waits run on
     * coroutine time.
     */
    private suspend fun scanLoop() {
        while (scope.isActive) {
            synchronized(scanSignal) { lastScanMono = clock.monotonicMillis() }
            scans.incrementAndGet()
            try {
                backfill.scanned(localSource.scan())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e(TAG, "Scan failed", e)
            }
            val wait = if (display.state.value.on) scanInterval else nightScanInterval
            withTimeoutOrNull(wait.toMillis()) { scanSignal.receive() }
        }
    }

    companion object {
        const val DEFAULT_PORT = 8080
        const val SERVER_RETRY_FIRST_MS = 30_000L
        private const val UPTIME_RECORD_MS = 60_000L
        private const val RESTART_DELAY_MS = 1_000L
        const val SERVER_RETRY_MAX_MS = 5 * 60_000L
        private const val MAX_RETRY_DOUBLINGS = 10
        private const val HANDLER_THREADS = 8
        private const val LAN_ADDRESS_TTL_MS = 5_000L
        private const val STOP_WAIT_MS = 5_000L

        /** How often the trash is checked for entries to purge (spec "Retention of the trash"). */
        private const val TRASH_CHECK_MS = 60 * 60_000L

        /** A client's fetch scans the folder only when the last scan began longer ago than this. */
        private const val CLIENT_SCAN_MS = 15_000L
        private const val TAG = "core"

        /** `http://<ip>:<port>/`, without the port for port 80. */
        fun adminUrl(
            ip: String,
            port: Int,
        ): String = if (port == 80) "http://$ip/" else "http://$ip:$port/"
    }
}
