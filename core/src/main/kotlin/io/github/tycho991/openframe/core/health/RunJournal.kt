package io.github.tycho991.openframe.core.health

import io.github.tycho991.openframe.core.store.AtomicFile
import io.github.tycho991.openframe.core.store.StoreJson
import kotlinx.serialization.Serializable
import java.io.File

/** How the previous run of the app process ended (spec `kiosk-host`, "Self-healing"). */
enum class RunEnd(
    val description: String,
) {
    /** No earlier run is recorded: the first start, or `runs.json` was not readable. */
    FIRST("no earlier run recorded"),

    /** Another installation: another version code or another `lastUpdateTime`. */
    REPLACED("a new installation"),

    /** Announced by [RunJournal.plannedExit] or [RunJournal.systemScreenOpened] and still valid. */
    PLANNED("a planned end"),

    /** Another `boot_id`, or without one a lower uptime than the previous run recorded. */
    REBOOTED("a device restart"),

    /** A Java crash, recorded by the crash handler. */
    CRASHED("a Java crash"),

    /** Everything else: a native crash or a kill by the firmware, the low-memory killer or the system. */
    DIED("an unexplained end"),
}

enum class HealthMode { NORMAL, SAFE }

/** Since when and why the frame is in safe mode. */
data class SafeMode(
    val sinceMs: Long,
    val reason: String,
)

/** What the journal decided at the start of this run. */
data class HealthState(
    val mode: HealthMode = HealthMode.NORMAL,
    val safeMode: SafeMode? = null,
    /** Early ends in a row before this run. */
    val recentEarlyEnds: Int = 0,
    val previousEnd: RunEnd = RunEnd.FIRST,
    /** The class of the exception of a [RunEnd.CRASHED] end. */
    val previousCrash: String? = null,
    /** The first run after leaving safe mode: an early end in it leads straight back. */
    val probation: Boolean = false,
    val bootIdReadable: Boolean = false,
    /** A system screen from the device menu has a record that is still open (see [RunJournal.systemScreenOpened]). */
    val systemScreenOpen: Boolean = false,
    /**
     * Learned: a start came within [RunJournal.SYSTEM_SCREEN_END_WINDOW_MS] after a system-screen record was made or
     * renewed, without a Java crash, so this device's firmware ends the app when another activity starts.
     */
    val systemScreenEndsApp: Boolean = false,
)

/**
 * The run journal (design decision 5 of `crash-resilience`): at each start it records how the previous run of the app
 * process ended, counts early ends and decides on safe mode. It keeps its state in [file] through [AtomicFile], or only
 * in memory without one, and never throws: a broken or unwritable file means a normal start.
 *
 * An early end is a run that ended [RunEnd.CRASHED] or [RunEnd.DIED] before [markStable]. [EARLY_ENDS_FOR_SAFE_MODE] in
 * a row lead to safe mode; a stable run and the ends [RunEnd.REBOOTED], [RunEnd.PLANNED] and [RunEnd.REPLACED] reset
 * the count. Safe mode ends with a new installation, or with [requestNormalStart] before a restart (from the device
 * menu, the API or the probe); the run after that is a probation run.
 *
 * A system screen opened from the device menu has a record of its own next to the planned end of an update or a
 * restart (design decision 3 of `open-android-settings`): each start of the same boot and installation carries a valid
 * one into the new run, renewed for [SYSTEM_SCREEN_VALID_MS], so any number of ends under the screen count as planned;
 * [cancelSystemScreen] withdraws it when the slideshow is back, and a Java crash wins over it.
 */
class RunJournal(
    private val file: File?,
) {
    private var stored = Stored()

    /** Records the start of this run and decides how it runs. [installId] is the version code and `lastUpdateTime`. */
    @Synchronized
    fun onProcessStart(
        wallMs: Long,
        elapsedMs: Long,
        bootId: String?,
        installId: String,
    ): HealthState {
        val previous = read()
        val end = previous?.let { classify(it, elapsedMs, bootId, installId) } ?: RunEnd.FIRST
        val early = (end == RunEnd.CRASHED || end == RunEnd.DIED) && previous?.stable == false
        val earlyEnds = if (early) (previous?.earlyEnds ?: 0) + 1 else 0
        var safe = previous?.safeSinceMs?.let { SafeMode(it, previous.safeReason ?: "") }
        var probeIndex = previous?.probeIndex ?: 0
        var probation = false
        when {
            end == RunEnd.REPLACED || end == RunEnd.FIRST -> {
                safe = null
                probeIndex = 0
            }

            previous?.normalStartRequested == true -> {
                // Left safe mode on request or by the probe: this run tries normal mode.
                probation = safe != null
            }

            previous?.probation == true && early -> {
                // The probation run ended early: back into safe mode, and the next probe waits twice as long.
                safe = SafeMode(wallMs, "early end after leaving safe mode")
                probeIndex = minOf(probeIndex + 1, PROBE_HOURS.size - 1)
            }

            safe != null && previous?.inSafeMode == true -> {
                // Safe mode goes on, for example after a device restart.
            }

            earlyEnds >= EARLY_ENDS_FOR_SAFE_MODE -> {
                safe = SafeMode(wallMs, "$earlyEnds early ends in a row")
                probeIndex = 0
            }

            else -> {
                safe = null
            }
        }
        val mode = if (safe != null && !probation) HealthMode.SAFE else HealthMode.NORMAL
        // A valid system-screen record of the same boot and installation goes on, renewed from this start.
        val screen = previous?.systemScreen?.takeIf { previous.sameRun(elapsedMs, bootId, installId) && it.validAt(elapsedMs) }
        val learned = screen != null && previous?.javaCrash == null && elapsedMs - screen.atElapsedMs <= SYSTEM_SCREEN_END_WINDOW_MS
        val endsApp = learned || (previous?.systemScreenEndsApp == true && end != RunEnd.REPLACED)
        stored =
            Stored(
                bootId = bootId,
                installId = installId,
                lastElapsedMs = elapsedMs,
                earlyEnds = earlyEnds,
                safeSinceMs = safe?.sinceMs,
                safeReason = safe?.reason,
                inSafeMode = mode == HealthMode.SAFE,
                probeIndex = probeIndex,
                probation = probation,
                systemScreen = screen?.copy(atElapsedMs = elapsedMs, validForMs = SYSTEM_SCREEN_VALID_MS),
                systemScreenEndsApp = endsApp,
            )
        write()
        return HealthState(
            mode,
            safe.takeIf { mode == HealthMode.SAFE },
            earlyEnds,
            end,
            previous?.javaCrash,
            probation,
            bootId != null,
            systemScreenOpen = screen != null,
            systemScreenEndsApp = endsApp,
        )
    }

    private fun classify(
        previous: Stored,
        elapsedMs: Long,
        bootId: String?,
        installId: String,
    ): RunEnd {
        val sameBoot = previous.sameBoot(elapsedMs, bootId)
        return when {
            previous.installId != installId -> RunEnd.REPLACED

            previous.planned?.validAt(elapsedMs) == true && sameBoot -> RunEnd.PLANNED

            !sameBoot -> RunEnd.REBOOTED

            // No firmware end is a Java crash, so a crash under a system screen still counts (design decision 3).
            previous.javaCrash != null -> RunEnd.CRASHED

            previous.systemScreen?.validAt(elapsedMs) == true -> RunEnd.PLANNED

            else -> RunEnd.DIED
        }
    }

    /** The uptime while the run goes on, once a minute, so that a device restart is recognized without `boot_id`. */
    @Synchronized
    fun recordUptime(elapsedMs: Long) {
        stored = stored.copy(lastElapsedMs = elapsedMs)
        write()
    }

    /** From the crash handler: this run ends with a Java crash of [errorClass]. */
    @Synchronized
    fun onJavaCrash(errorClass: String) {
        stored = stored.copy(javaCrash = errorClass)
        write()
    }

    /** The run lasted [STABLE_AFTER_MS] in normal mode: it is no early end, and a probation run ends safe mode. */
    @Synchronized
    fun markStable() {
        stored = stored.copy(stable = true, earlyEnds = 0, probation = false)
        if (!stored.inSafeMode) stored = stored.copy(safeSinceMs = null, safeReason = null, probeIndex = 0)
        write()
    }

    /** An end of the process for [reason] is intended within [validForMs] from [elapsedMs]; it counts as planned. */
    @Synchronized
    fun plannedExit(
        reason: String,
        elapsedMs: Long,
        validForMs: Long,
    ) {
        stored = stored.copy(planned = Planned(reason, elapsedMs, validForMs))
        write()
    }

    @Synchronized
    fun cancelPlannedExit() {
        stored = stored.copy(planned = null)
        write()
    }

    /**
     * The device menu opens a system screen for [reason] at [elapsedMs]: ends of the process count as planned while the
     * record is valid, [validForMs] from now and renewed at each start. It leaves the planned end of an update alone.
     */
    @Synchronized
    fun systemScreenOpened(
        reason: String,
        elapsedMs: Long,
        validForMs: Long = SYSTEM_SCREEN_VALID_MS,
    ) {
        stored = stored.copy(systemScreen = Planned(reason, elapsedMs, validForMs))
        write()
    }

    /** The slideshow is back in front: withdraws the system-screen record, not the planned end of an update. */
    @Synchronized
    fun cancelSystemScreen() {
        if (stored.systemScreen == null) return
        stored = stored.copy(systemScreen = null)
        write()
    }

    /** A system-screen record exists and is valid at [elapsedMs]: the crash handler then does not restart the display. */
    @Synchronized
    fun systemScreenOpen(elapsedMs: Long): Boolean = stored.systemScreen?.validAt(elapsedMs) == true

    /** Before a restart that leaves safe mode: the next start runs in normal mode, as a probation run. */
    @Synchronized
    fun requestNormalStart() {
        stored = stored.copy(normalStartRequested = true)
        write()
    }

    /** Safe mode since [sinceMs] without three early ends, for tests and the test server. */
    @Synchronized
    internal fun forceSafe(sinceMs: Long) {
        stored = stored.copy(safeSinceMs = sinceMs, safeReason = "test", inSafeMode = true)
        write()
    }

    /** When the probe leaves safe mode, measured from when it began: 1 h, then doubling up to 24 h. */
    @Synchronized
    fun probeAtMs(): Long? {
        val since = stored.safeSinceMs?.takeIf { stored.inSafeMode } ?: return null
        return since + PROBE_HOURS[stored.probeIndex] * HOUR_MS
    }

    private fun read(): Stored? =
        try {
            file?.let { AtomicFile(it).readText() }?.let { StoreJson.decodeFromString(Stored.serializer(), it) }
        } catch (e: Throwable) {
            null
        }

    private fun write() {
        try {
            file?.let { AtomicFile(it).writeText(StoreJson.encodeToString(Stored.serializer(), stored)) }
        } catch (e: Throwable) {
            // The journal must never end the app; without it the next start is a normal one.
        }
    }

    @Serializable
    private data class Planned(
        val reason: String,
        val atElapsedMs: Long,
        val validForMs: Long,
    ) {
        fun validAt(elapsedMs: Long) = elapsedMs in atElapsedMs..atElapsedMs + validForMs
    }

    @Serializable
    private data class Stored(
        val bootId: String? = null,
        val installId: String = "",
        val lastElapsedMs: Long = 0,
        val stable: Boolean = false,
        val javaCrash: String? = null,
        val planned: Planned? = null,
        val earlyEnds: Int = 0,
        val safeSinceMs: Long? = null,
        val safeReason: String? = null,
        val inSafeMode: Boolean = false,
        val probeIndex: Int = 0,
        val probation: Boolean = false,
        val normalStartRequested: Boolean = false,
        /** The record of a system screen opened from the device menu, apart from [planned] (design decision 3). */
        val systemScreen: Planned? = null,
        /** Learned at a start: the firmware ends the app when another activity starts (design decision 1b). */
        val systemScreenEndsApp: Boolean = false,
    ) {
        fun sameBoot(
            elapsedMs: Long,
            bootId: String?,
        ) = if (bootId != null && this.bootId != null) bootId == this.bootId else elapsedMs >= lastElapsedMs

        fun sameRun(
            elapsedMs: Long,
            bootId: String?,
            installId: String,
        ) = installId == this.installId && sameBoot(elapsedMs, bootId)
    }

    companion object {
        const val EARLY_ENDS_FOR_SAFE_MODE = 3
        const val STABLE_AFTER_MS = 10 * 60_000L
        const val HOUR_MS = 3_600_000L

        /** How long a system-screen record stays valid, from its making and from each start that renews it. */
        const val SYSTEM_SCREEN_VALID_MS = 10 * 60_000L

        /** A start this soon after a system-screen record was made or renewed teaches that the firmware ended the app. */
        const val SYSTEM_SCREEN_END_WINDOW_MS = 60_000L

        /** The waits of the probe in hours: 1, 2, 4, 8, 16, then 24. */
        val PROBE_HOURS = listOf(1L, 2L, 4L, 8L, 16L, 24L)
    }
}
