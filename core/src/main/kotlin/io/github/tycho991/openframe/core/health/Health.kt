package io.github.tycho991.openframe.core.health

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The health of this run for the core and for app-auto-update (design decision 9 of `crash-resilience`).
 *
 * - [state] tells the mode, since when and why it is in safe mode, [HealthState.recentEarlyEnds] and how the previous
 *   run ended. A new installation always counts as the planned end [RunEnd.REPLACED] and ends safe mode.
 * - [plannedExit] announces an end of the process, for example before committing a `PackageInstaller` session, valid
 *   for [plannedExit]'s `validForMs`; [cancelPlannedExit] withdraws it on cancel, failure or abandon.
 *
 * [elapsedMs] is the device's uptime (`SystemClock.elapsedRealtime` on Android), which the journal compares across
 * runs.
 */
class Health(
    private val journal: RunJournal,
    initial: HealthState,
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

    /** After [RunJournal.STABLE_AFTER_MS] in normal mode. */
    fun markStable() {
        journal.markStable()
        _state.value = _state.value.copy(probation = false, recentEarlyEnds = 0)
    }

    /** The uptime, once a minute while the run goes on. */
    fun recordUptime() = journal.recordUptime(elapsedMs())

    /** Before the restart that leaves safe mode. */
    fun requestNormalStart() = journal.requestNormalStart()

    /** When the probe leaves safe mode, in wall-clock milliseconds; `null` outside safe mode. */
    fun probeAtMs(): Long? = journal.probeAtMs()

    companion object {
        /** A run in normal mode with a journal in memory only, for tests and the test server. */
        fun normal(): Health = Health(RunJournal(null), HealthState(), { 0L })

        /** A run in safe mode with a journal in memory only, for tests and the test server. */
        fun safe(sinceMs: Long = 0L): Health =
            Health(
                RunJournal(null),
                HealthState(HealthMode.SAFE, SafeMode(sinceMs, "test"), RunJournal.EARLY_ENDS_FOR_SAFE_MODE),
                { 0L },
            )
    }
}
