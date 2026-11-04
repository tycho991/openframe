package io.github.tycho991.openframe.core.update

import io.github.tycho991.openframe.core.display.DisplayPolicy
import io.github.tycho991.openframe.core.health.Health
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.ApkVerdict
import io.github.tycho991.openframe.core.platform.AppInstaller
import io.github.tycho991.openframe.core.platform.Clock
import io.github.tycho991.openframe.core.platform.InstallResult
import io.github.tycho991.openframe.core.platform.PlatformInfo
import io.github.tycho991.openframe.core.settings.Settings
import io.github.tycho991.openframe.core.store.JsonStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/** A downloaded and verified update in [FrameDirs.updates][io.github.tycho991.openframe.core.FrameDirs.updates]. */
@Serializable
data class ReadyApk(
    val version: String,
    val versionCode: Long,
    val file: String,
    val sha256: String,
    val size: Long,
)

/** An APK that failed a deterministic check; not downloaded again while the installed version stays [installedCode]. */
@Serializable
data class RejectedApk(
    val sha256: String,
    val installedCode: Long,
    val error: UpdateError,
)

/**
 * An installation dialog that was opened. The uptime values count only with the same [bootId]; without a readable
 * `boot_id` an uptime below [requestedAtElapsed] means the device restarted.
 */
@Serializable
data class InstallRequested(
    val version: String,
    val versionCode: Long,
    val sessionId: Int,
    val bootId: String? = null,
    val requestedAtElapsed: Long,
    val deadlineElapsed: Long,
)

/** `update.json`. */
@Serializable
data class StoredUpdate(
    /** Wall clock of the last attempt to ask GitHub, successful or not. */
    val lastAttemptAt: Long? = null,
    /** Failed attempts in a row. */
    val failures: Int = 0,
    val etag: String? = null,
    val release: Release? = null,
    val lastCheck: LastCheck? = null,
    val error: UpdateError? = null,
    /** No request to GitHub before this wall-clock time. */
    val rateLimitResetAt: Long? = null,
    val ready: ReadyApk? = null,
    val rejected: RejectedApk? = null,
    val installRequested: InstallRequested? = null,
)

/**
 * Finds, downloads, verifies and installs new versions of openframe (spec `app-update`, design decisions 3, 4 and 6 of
 * `app-auto-update`). It runs in both modes of [health]; in safe mode, or after early ends, its first attempt comes at
 * once, so that a fixed release can replace one that crashes.
 *
 * One loop on [scope] makes the automatic attempts; [check] asks it for one at once. A [Mutex] keeps check, download
 * and installing apart. [install] never blocks: it answers with an [InstallRequest] and opens the dialog on [scope].
 * Results from Android arrive at [onInstallResult], also before [start] loaded the stored state, and are applied then.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateManager(
    private val scope: CoroutineScope,
    private val store: JsonStore<StoredUpdate>,
    private val source: ReleaseSource,
    private val installer: AppInstaller,
    private val platform: PlatformInfo,
    private val health: Health,
    private val settings: StateFlow<Settings>,
    private val dir: File,
    private val clock: Clock,
    private val log: AppLog,
) {
    private enum class Running { NONE, CHECKING, DOWNLOADING }

    /** An open (or opening) installation dialog: its session once created, and its end on the monotonic clock. */
    private class Dialog(
        val version: String,
        val untilMono: Long,
        var sessionId: Int? = null,
    )

    private val lock = Any()
    private val work = Mutex()
    private val signal = Channel<Unit>(Channel.CONFLATED)

    // Guarded by [lock].
    private var stored = StoredUpdate()
    private var loaded = false
    private var unsupported = false
    private var running = Running.NONE
    private var dialog: Dialog? = null
    private val buffered = mutableListOf<Pair<Int, InstallResult>>()

    /** Client requests to install are refused until then (monotonic), after a cancelled or unanswered dialog. */
    private var cooldownUntil: Long? = null

    /** A check asked for by a client, or a download to repeat; taken by the loop. */
    private var requested = false
    private var refetch = false
    private var retryWithin: Long? = null
    private var firstAtOnce = false
    private var startMono = 0L

    private val _state = MutableStateFlow(UpdateState(platform.appVersion, UpdatePhase.IDLE))

    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** The state with [UpdateState.installAllowed] read afresh, for the API and the device menu. */
    fun current(): UpdateState = _state.value.copy(installAllowed = installer.installAllowed)

    fun start() {
        startMono = clock.monotonicMillis()
        val h = health.state.value
        firstAtOnce = health.safe || h.recentEarlyEnds > 0
        scope.launch {
            load()
            loop()
        }
        scope.launch {
            settings
                .map { it.autoUpdateCheck }
                .distinctUntilChanged()
                .drop(1)
                .collect { signal.send(Unit) }
        }
    }

    suspend fun flush() = store.flush()

    // Loading

    private fun load() {
        val code = platform.appVersionCode
        val cert = installer.installedCertificateSha256()
        synchronized(lock) {
            stored = store.load() ?: StoredUpdate()
            val now = clock.millis()
            stored.lastAttemptAt?.let { if (it > now) stored = stored.copy(lastAttemptAt = null) }
            when {
                !OfficialCertificate.matches(cert) -> {
                    unsupported = true
                    log.i(TAG, "Updates off: the app is not signed with the official certificate (${cert ?: "not readable"})")
                }

                code == null -> {
                    unsupported = true
                    log.w(TAG, "Updates off: the installed version code is unknown")
                }
            }
            if (code != null && stored.rejected?.installedCode != code) stored = stored.copy(rejected = null)
        }
        reconcileInstall(code)
        checkKept(code)
        synchronized(lock) {
            loaded = true
            val results = buffered.toList()
            buffered.clear()
            results.forEach { (id, result) -> applyResult(id, result) }
            save()
            publish()
        }
    }

    /** At the start: an installation that was asked for before this run, and sessions left open (design decision 6). */
    private fun reconcileInstall(code: Long?) {
        val open =
            try {
                installer.openSessions()
            } catch (e: Exception) {
                log.e(TAG, "Reading the open installation sessions failed", e)
                emptySet()
            }
        val req = synchronized(lock) { stored.installRequested }
        for (id in open) {
            if (id != req?.sessionId) {
                log.i(TAG, "Installation session $id left from before abandoned")
                abandon(id)
            }
        }
        if (req == null) return
        val result = synchronized(lock) { buffered.lastOrNull { it.first == req.sessionId }?.second }
        when {
            code != null && code >= req.versionCode -> {
                log.i(TAG, "Updated to ${req.version}")
                synchronized(lock) { stored = stored.copy(installRequested = null, error = null) }
            }

            result != null -> {
                // Applied after loading, like every result that arrived before it.
                val rest = (req.deadlineElapsed - clock.elapsedRealtimeMillis()).coerceAtLeast(0)
                synchronized(lock) { dialog = Dialog(req.version, clock.monotonicMillis() + rest, req.sessionId) }
            }

            deviceRestarted(req) -> {
                log.i(TAG, "The device restarted while the installation dialog was open: discarded")
                if (req.sessionId in open) abandon(req.sessionId)
                synchronized(lock) { endInstall(UpdateErrorCode.INSTALL_UNANSWERED, UNANSWERED, cooldown = false) }
            }

            req.sessionId in open -> {
                val rest = req.deadlineElapsed - clock.elapsedRealtimeMillis()
                if (rest <= 0) {
                    log.i(TAG, "The installation dialog for ${req.version} passed its deadline: discarded")
                    unanswered(req.sessionId, cooldown = false)
                } else {
                    log.i(TAG, "Installation dialog for ${req.version} still open, ${rest / 1000} s left")
                    health.plannedExit(PLANNED_REASON, rest + PLANNED_MARGIN_MS)
                    synchronized(lock) { dialog = Dialog(req.version, clock.monotonicMillis() + rest, req.sessionId) }
                }
            }

            else -> {
                log.i(TAG, "The installation dialog for ${req.version} ended without an answer")
                synchronized(lock) { endInstall(UpdateErrorCode.INSTALL_UNANSWERED, UNANSWERED, cooldown = false) }
            }
        }
    }

    private fun deviceRestarted(req: InstallRequested): Boolean {
        val boot = health.bootId
        return if (boot != null && req.bootId != null) boot != req.bootId else clock.elapsedRealtimeMillis() < req.requestedAtElapsed
    }

    /** A kept download must still be there with its checksum, and newer than the installed version (design decision 4). */
    private fun checkKept(code: Long?): Boolean {
        val ready = synchronized(lock) { stored.ready } ?: return false
        val file = File(dir, ready.file)
        val problem =
            when {
                code != null && ready.versionCode <= code -> {
                    log.i(TAG, "Download of ${ready.version} no longer newer: deleted")
                    synchronized(lock) { stored = stored.copy(error = null, lastCheck = LastCheck(clock.millis(), CheckResult.UP_TO_DATE)) }
                    null
                }

                !file.isFile -> {
                    "missing"
                }

                sha256(file) != ready.sha256 -> {
                    "changed"
                }

                else -> {
                    return true
                }
            }
        problem?.let { log.w(TAG, "Kept download of ${ready.version} $it: downloading it again") }
        file.delete()
        synchronized(lock) {
            stored = stored.copy(ready = null)
            if (problem != null) refetch = true
        }
        return false
    }

    // Checking

    private suspend fun loop() {
        while (scope.isActive) {
            try {
                expireDialog()
                if (synchronized(lock) { due() }) attempt()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.e(TAG, "Update step failed", e)
            }
            val wait = synchronized(lock) { waitMs() }
            select {
                signal.onReceive {}
                onTimeout(wait) {}
            }
        }
    }

    /**
     * Milliseconds until the loop has something to do: the dialog's deadline or the next automatic attempt behind its
     * gates. At most [TICK_MS], so that a clock that is set meanwhile is noticed; at least [MIN_WAIT_MS].
     */
    private fun waitMs(): Long {
        val mono = clock.monotonicMillis()
        val now = clock.millis()
        var wait = TICK_MS
        dialog?.let { wait = minOf(wait, it.untilMono - mono) }
        val next = nextAttemptAt()
        if (next != null && dialog == null && settings.value.autoUpdateCheck && DisplayPolicy.clockSet(clock.now())) {
            var until = next - now
            if (!firstAtOnce) until = maxOf(until, startMono + START_DELAY_MS - mono)
            stored.rateLimitResetAt?.let { until = maxOf(until, it - now) }
            wait = minOf(wait, until)
        }
        return wait.coerceAtLeast(MIN_WAIT_MS)
    }

    /** When the next automatic attempt is due on the wall clock, without the gates of [due]. */
    private fun nextAttemptAt(): Long? {
        if (unsupported || !loaded) return null
        val s = stored
        val last = s.lastAttemptAt ?: return clock.millis()
        if (firstAtOnce || refetch) return last + MIN_INTERVAL_MS
        val retry = retryWithin?.takeIf { s.failures > 0 }?.let { maxOf(it, last + MIN_INTERVAL_MS) }
        val next = last + retryDelayMs(s.failures)
        return retry?.let { minOf(it, next) } ?: next
    }

    /** Whether the loop asks GitHub now: on request, or automatically when the gates are open. */
    private fun due(): Boolean {
        if (unsupported || !loaded || dialog != null) return false
        val now = clock.millis()
        if (stored.rateLimitResetAt?.let { now < it } == true) {
            if (requested) {
                requested = false
                publish()
            }
            return false
        }
        if (requested) return true
        if (!settings.value.autoUpdateCheck || !DisplayPolicy.clockSet(clock.now())) return false
        if (!firstAtOnce && clock.monotonicMillis() < startMono + START_DELAY_MS) return false
        return now >= (nextAttemptAt() ?: return false)
    }

    /** After a network change: a waiting retry comes within a minute (spec `app-update`, Retry after a failed check). */
    fun networkChanged() {
        synchronized(lock) {
            if (stored.failures == 0) return
            retryWithin = clock.millis()
        }
        signal.trySend(Unit)
    }

    /**
     * A check on request (spec `app-update`, Check on request). Not while a check, a download or the dialog runs, within
     * a minute of the last attempt, or before GitHub's rate limit resets; the answer is the state either way.
     */
    fun check(): UpdateState {
        synchronized(lock) {
            val now = clock.millis()
            val recent = stored.lastAttemptAt?.let { now - it in 0 until MIN_INTERVAL_MS } == true
            val limited = stored.rateLimitResetAt?.let { now < it } == true
            if (unsupported || !loaded || running != Running.NONE || requested || dialog != null || recent || limited) return current()
            requested = true
            publish()
        }
        signal.trySend(Unit)
        return current()
    }

    private suspend fun attempt() =
        work.withLock {
            val now = clock.millis()
            val etag =
                synchronized(lock) {
                    running = Running.CHECKING
                    requested = false
                    retryWithin = null
                    stored = stored.copy(lastAttemptAt = now)
                    publish()
                    stored.etag.takeIf { stored.release != null }
                }
            try {
                val fetched = source.latest(etag)
                val release =
                    synchronized(lock) {
                        if (fetched != null) stored = stored.copy(release = fetched.release, etag = fetched.etag)
                        stored.release
                    } ?: throw UpdateException(UpdateErrorCode.UPDATE_FAILED, "GitHub answered 304 without a stored release")
                evaluate(release, now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateException) {
                failed(e, now)
            } catch (e: Throwable) {
                log.e(TAG, "Update check failed unexpectedly", e)
                failed(UpdateException(UpdateErrorCode.UPDATE_FAILED, "Unexpected error (${e.javaClass.simpleName})"), now)
            } finally {
                synchronized(lock) {
                    running = Running.NONE
                    firstAtOnce = false
                    refetch = false
                    save()
                    publish()
                }
            }
        }

    private fun evaluate(
        release: Release,
        now: Long,
    ) {
        val installed =
            platform.appVersionCode ?: throw UpdateException(UpdateErrorCode.UPDATE_FAILED, "The installed version code is unknown")
        val version = release.version
        val code = version?.let(Versions::code)
        if (version == null || code == null || code <= installed) {
            log.i(TAG, "Up to date: the latest release is ${release.tag}")
            deleteDownloads(keep = null)
            synchronized(lock) {
                stored = stored.copy(failures = 0, error = null, ready = null, lastCheck = LastCheck(now, CheckResult.UP_TO_DATE))
            }
            return
        }
        val apks = release.assets.filter { APK.matches(it.name) }
        if (apks.size != 1) {
            throw UpdateException(
                UpdateErrorCode.UPDATE_NO_APK,
                "Release $version has ${if (apks.isEmpty()) "no" else "more than one"} APK of openframe",
            )
        }
        val asset = apks.single()
        val sha =
            asset.digest
                ?.let(DIGEST::matchEntire)
                ?.groupValues
                ?.get(1)
                ?.lowercase()
                ?: throw UpdateException(UpdateErrorCode.UPDATE_NO_CHECKSUM, "Release $version states no SHA-256 for its APK")
        val (ready, rejected) = synchronized(lock) { stored.ready to stored.rejected }
        when {
            ready != null && ready.sha256 == sha && ready.versionCode == code -> {
                Unit
            }

            rejected != null && rejected.sha256 == sha && rejected.installedCode == installed -> {
                synchronized(lock) {
                    stored = stored.copy(failures = 0, error = rejected.error, lastCheck = LastCheck(now, CheckResult.FAILED))
                }
                log.i(TAG, "Release $version was rejected before (${rejected.error.code.api}): not downloaded again")
                return
            }

            else -> {
                download(version, code, asset, sha, installed)
            }
        }
        synchronized(lock) { stored = stored.copy(failures = 0, error = null, lastCheck = LastCheck(now, CheckResult.UPDATE_FOUND)) }
    }

    private fun download(
        version: String,
        code: Long,
        asset: ReleaseAsset,
        sha: String,
        installed: Long,
    ) {
        dir.mkdirs()
        val needed = 2 * asset.size + RESERVE_BYTES
        if (platform.freeBytes(dir) < needed) {
            throw UpdateException(UpdateErrorCode.UPDATE_STORAGE, "Not enough free storage for the update (${needed / MIB} MiB needed)")
        }
        synchronized(lock) {
            running = Running.DOWNLOADING
            stored = stored.copy(ready = null)
            publish()
        }
        deleteDownloads(keep = null)
        val name = "openframe-$version.apk"
        val part = File(dir, "$name.part")
        log.i(TAG, "Downloading $version (${asset.size / 1024} KiB)")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            FileOutputStream(part).use { out ->
                source.download(asset.url, DigestOutputStream(out, digest), asset.size)
                out.fd.sync()
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        if (part.length() != asset.size || hex(digest.digest()) != sha) {
            part.delete()
            throw UpdateException(UpdateErrorCode.UPDATE_CHECKSUM, "The download of $version does not match the checksum of the release")
        }
        val file = File(dir, name)
        if (!part.renameTo(file)) throw UpdateException(UpdateErrorCode.UPDATE_FAILED, "Renaming the download failed")
        ApkStructure.problem(file)?.let { reject(file, sha, installed, UpdateErrorCode.UPDATE_INVALID_APK, "The APK of $version has $it") }
        when (val verdict = installer.verify(file)) {
            ApkVerdict.Ok -> {
                Unit
            }

            is ApkVerdict.Rejected -> {
                reject(
                    file,
                    sha,
                    installed,
                    verdict.code,
                    "The APK of $version is not installable: ${verdict.detail}",
                )
            }
        }
        log.i(TAG, "Update $version downloaded and verified")
        synchronized(lock) { stored = stored.copy(ready = ReadyApk(version, code, name, sha, asset.size)) }
    }

    /** A deterministic failure: the file goes, and the same APK is not downloaded again for this installed version. */
    private fun reject(
        file: File,
        sha: String,
        installed: Long,
        code: UpdateErrorCode,
        message: String,
    ): Nothing {
        file.delete()
        synchronized(lock) { stored = stored.copy(rejected = RejectedApk(sha, installed, UpdateError(code, message))) }
        throw UpdateException(code, message, deterministic = true)
    }

    private fun failed(
        e: UpdateException,
        now: Long,
    ) {
        log.w(TAG, "Update check failed (${e.code.api}): ${e.message}")
        e.detail?.let { log.w(TAG, "Certificate chain: $it") }
        synchronized(lock) {
            stored =
                stored.copy(
                    failures = if (e.deterministic) 0 else stored.failures + 1,
                    error = UpdateError(e.code, e.message),
                    lastCheck = LastCheck(now, CheckResult.FAILED),
                    rateLimitResetAt = e.resetAtMs ?: stored.rateLimitResetAt?.takeIf { it > now },
                )
        }
    }

    private fun deleteDownloads(keep: String?) {
        dir.listFiles()?.filter { it.name != keep }?.forEach { it.delete() }
    }

    // Installing

    /**
     * Opens the installation dialog for the ready update (spec `app-update`, Installation on request of a client).
     * [client] names the requesting client; `null` is the device menu, which no cooldown holds back. Never blocks.
     */
    fun install(client: String?): InstallRequest {
        val by = client ?: "the device menu"
        val dialog =
            synchronized(lock) {
                val ready = stored.ready
                val refusal =
                    when {
                        dialog != null -> InstallRequest.InProgress
                        unsupported || !loaded || ready == null -> InstallRequest.NotReady
                        !installer.installAllowed -> InstallRequest.NotAllowed
                        client != null && cooldownUntil?.let { clock.monotonicMillis() < it } == true -> InstallRequest.Cooldown
                        else -> null
                    }
                if (refusal != null) {
                    log.i(TAG, "Installation requested by $by refused: ${refusal.code}")
                    return refusal
                }
                log.i(TAG, "Installation of ${ready!!.version} requested by $by")
                Dialog(ready.version, clock.monotonicMillis() + DEADLINE_MS).also {
                    this.dialog = it
                    publish()
                }
            }
        scope.launch { openDialog(dialog) }
        return InstallRequest.Accepted
    }

    private suspend fun openDialog(d: Dialog) =
        work.withLock {
            val ready = synchronized(lock) { stored.ready }
            val file = ready?.let { File(dir, it.file) }
            if (ready == null || file == null || !checkKept(platform.appVersionCode)) {
                synchronized(lock) {
                    if (dialog === d) dialog = null
                    publish()
                }
                signal.trySend(Unit)
                return@withLock
            }
            var sessionId: Int? = null
            try {
                health.plannedExit(PLANNED_REASON, DEADLINE_MS + PLANNED_MARGIN_MS)
                val id = installer.createSession()
                sessionId = id
                val now = clock.elapsedRealtimeMillis()
                val req = InstallRequested(ready.version, ready.versionCode, id, health.bootId, now, now + DEADLINE_MS)
                // On disk before Android can answer: a process end after this point finds the session again.
                val snapshot =
                    synchronized(lock) {
                        stored = stored.copy(installRequested = req)
                        stored
                    }
                store.saveNow(snapshot)
                synchronized(lock) {
                    if (dialog !== d) return@withLock
                    d.sessionId = id
                }
                installer.commit(id, file)
                log.i(TAG, "Installation dialog for ${ready.version} opened (session $id)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.e(TAG, "Opening the installation dialog failed", e)
                synchronized(lock) {
                    if (dialog !== d) return@withLock
                    dialog = null
                    endInstall(
                        UpdateErrorCode.INSTALL_FAILED,
                        "The installation dialog could not be opened (${e.javaClass.simpleName})",
                        false,
                    )
                }
                sessionId?.let(::abandon)
            }
        }

    /** A result of an installation session from Android (design decision 6). */
    fun onInstallResult(
        sessionId: Int,
        result: InstallResult,
    ) {
        synchronized(lock) {
            if (!loaded) {
                buffered += sessionId to result
                return
            }
            applyResult(sessionId, result)
        }
    }

    private fun applyResult(
        sessionId: Int,
        result: InstallResult,
    ) {
        val d = dialog
        if (d == null || d.sessionId != sessionId) {
            log.i(TAG, "Result ${result.javaClass.simpleName} of installation session $sessionId dropped: not the open dialog")
            return
        }
        when (result) {
            InstallResult.Success -> {
                log.i(TAG, "Update ${d.version} installed")
            }

            InstallResult.Cancelled -> {
                log.i(TAG, "Installation dialog for ${d.version} cancelled")
                endInstall(UpdateErrorCode.INSTALL_CANCELLED, "The installation dialog was cancelled on the frame", cooldown = true)
            }

            is InstallResult.Failed -> {
                log.w(TAG, "Installation of ${d.version} failed: ${result.message}")
                endInstall(UpdateErrorCode.INSTALL_FAILED, "Android could not install the update: ${result.message}", cooldown = false)
            }
        }
    }

    /** The dialog's 10 minutes are over: discarded, the update stays ready (spec `app-update`, Unanswered dialog). */
    private fun expireDialog() {
        val id =
            synchronized(lock) {
                val d = dialog ?: return
                if (clock.monotonicMillis() < d.untilMono || d.sessionId == null) return
                d.sessionId
            } ?: return
        log.i(TAG, "Nobody answered the installation dialog in ${DEADLINE_MS / 60_000} minutes: discarded")
        unanswered(id, cooldown = true)
    }

    private fun unanswered(
        sessionId: Int,
        cooldown: Boolean,
    ) {
        // The active ID goes first, so that the cancel Android reports for the abandoned session is dropped.
        synchronized(lock) { endInstall(UpdateErrorCode.INSTALL_UNANSWERED, UNANSWERED, cooldown) }
        abandon(sessionId)
    }

    /** Ends the dialog with [code]; the update stays ready. Under [lock]. */
    private fun endInstall(
        code: UpdateErrorCode,
        message: String,
        cooldown: Boolean,
    ) {
        dialog = null
        stored = stored.copy(installRequested = null, error = UpdateError(code, message))
        if (cooldown) cooldownUntil = clock.monotonicMillis() + COOLDOWN_MS
        health.cancelPlannedExit()
        if (loaded) {
            save()
            publish()
        }
    }

    private fun abandon(sessionId: Int) {
        try {
            installer.abandon(sessionId)
        } catch (e: Exception) {
            log.e(TAG, "Abandoning installation session $sessionId failed", e)
        }
    }

    // State

    /** Under [lock]. */
    private fun save() = store.save(stored)

    /** Under [lock]. */
    private fun publish() {
        val s = stored
        val phase =
            when {
                unsupported -> UpdatePhase.UNSUPPORTED
                dialog != null -> UpdatePhase.INSTALLING
                running == Running.CHECKING || requested -> UpdatePhase.CHECKING
                running == Running.DOWNLOADING -> UpdatePhase.DOWNLOADING
                s.ready != null -> UpdatePhase.READY
                else -> UpdatePhase.IDLE
            }
        _state.value =
            UpdateState(
                installedVersion = platform.appVersion,
                phase = phase,
                latest = s.release,
                lastCheck = s.lastCheck,
                error = s.error,
                installAllowed = installer.installAllowed,
                readyVersion = dialog?.version ?: s.ready?.version,
                installUntilMono = dialog?.untilMono,
            )
    }

    companion object {
        const val START_DELAY_MS = 3 * 60_000L
        const val MIN_INTERVAL_MS = 60_000L
        const val DEADLINE_MS = 10 * 60_000L
        const val COOLDOWN_MS = 30 * 60_000L

        /** The planned end outlasts the dialog's deadline. */
        const val PLANNED_MARGIN_MS = 5 * 60_000L
        const val PLANNED_REASON = "update"
        private const val TICK_MS = 30_000L
        private const val MIN_WAIT_MS = 1_000L
        private const val MIB = 1024L * 1024
        private const val RESERVE_BYTES = 100 * MIB
        private const val TAG = "update"
        private const val UNANSWERED = "Nobody answered the installation dialog on the frame"
        private val APK = Regex("^openframe-.*\\.apk$")
        private val DIGEST = Regex("^sha256:([0-9a-fA-F]{64})$")

        /** The wait after an attempt: 6 h after a success, then 15 min, 1 h and 6 h after failures in a row. */
        fun retryDelayMs(failures: Int): Long =
            when (failures) {
                0 -> 6 * 3_600_000L
                1 -> 15 * 60_000L
                2 -> 3_600_000L
                else -> 6 * 3_600_000L
            }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return hex(digest.digest())
        }

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}
