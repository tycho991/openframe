package io.github.tycho991.openframe.core.health

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The health of this run for the core and for app-auto-update (design decision 9 of `crash-resilience`).
 *
 * - [state] tells the mode, since when and why it is in safe mode, [HealthState.recentEarlyEnds] and how the previous
 *   run ended. A new installation always counts as the planned end [RunEnd.REPLACED] and ends safe mode.
 * - [plannedExit] announces an end of the process, for example before committing a `PackageInstaller` session, valid
 *   for [plannedExit]'s `validForMs`; [cancelPlannedExit] withdraws it on cancel, failure or abandon.
 * - [systemScreenOpened] and [cancelSystemScreen] do the same for a system screen from the device menu, in a record of
 *   its own that lasts until the slideshow is back.
 *
 * [elapsedMs] is the device's uptime (`SystemClock.elapsedRealtime` on Android), which the journal compares across
 * runs. [bootId] is the `boot_id` the journal read at the start, `null` where the kernel does not let the app read it;
 * a stored uptime counts only while it is the same.
 */
class Health(
    private val journal: RunJournal,
    initial: HealthState,
    val bootId: String? = null,
    private val elapsedMs: () -> Long,
) {
    private val _state = MutableStateFlow(initial)

    val state: StateFlow<HealthState> = _state.asStateFlow()

    val safe: Boolean get() = _state.value.mode == HealthMode.SAFE

    /** The process will end for [reason] within [validForMs]; that end counts neither as an early end nor as a crash. */
    fun plannedExit(
        reason: String,
        validForMs: Long,
    ) = journal.plannedExit(reason, elapsedMs(), validForMs)

    fun cancelPlannedExit() = journal.cancelPlannedExit()

    /**
     * The device menu opens a system screen for [reason]: ends of the process under it count as planned until
     * [cancelSystemScreen] (design decision 3 of `open-android-settings`).
     */
    fun systemScreenOpened(reason: String) {
        journal.systemScreenOpened(reason, elapsedMs())
        systemScreenOpenedInThisRun = true
        _state.update { it.copy(systemScreenOpen = true) }
    }

    /** The slideshow is back in front: withdraws the system-screen record, never the planned end of an update. */
    fun cancelSystemScreen() {
        journal.cancelSystemScreen()
        systemScreenOpenedInThisRun = false
        _state.update { it.copy(systemScreenOpen = false) }
    }

    /**
     * The open system screen was opened in this run, not carried in from the previous one: only then does this run hold
     * the display for it, and only then has the core's start not checked the HOME app after it.
     */
    @Volatile var systemScreenOpenedInThisRun = false
        private set

    /** The system-screen record is still valid now; after its 10 minutes the display hold has ended as well. */
    fun systemScreenValid(): Boolean = journal.systemScreenOpen(elapsedMs())

    /** After [RunJournal.STABLE_AFTER_MS] in normal mode. */
    fun markStable() {
        journal.markStable()
        _state.update { it.copy(probation = false, recentEarlyEnds = 0) }
    }

    /** The uptime, once a minute while the run goes on. */
    fun recordUptime() = journal.recordUptime(elapsedMs())

    /** Before the restart that leaves safe mode. */
    fun requestNormalStart() = journal.requestNormalStart()

    /** When the probe leaves safe mode, in wall-clock milliseconds; `null` outside safe mode. */
    fun probeAtMs(): Long? = journal.probeAtMs()

    companion object {
        /** A run in normal mode with a journal in memory only, for tests and the test server. */
        fun normal(bootId: String? = null): Health = Health(RunJournal(null), HealthState(bootIdReadable = bootId != null), bootId) { 0L }

        /** A run in safe mode with a journal in memory only, for tests and the test server. */
        fun safe(sinceMs: Long): Health =
            Health(
                RunJournal(null).also { it.forceSafe(sinceMs) },
                HealthState(HealthMode.SAFE, SafeMode(sinceMs, "test"), RunJournal.EARLY_ENDS_FOR_SAFE_MODE),
            ) { 0L }
    }
}
