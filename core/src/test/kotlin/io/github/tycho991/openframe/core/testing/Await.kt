package io.github.tycho991.openframe.core.testing

import org.junit.jupiter.api.Assertions.fail

/**
 * Polls [condition] until it holds, for state the core updates in the background (`stateIn`, workers, syncs).
 * Fails the test with [message] if it does not hold within [timeoutMs].
 */
fun waitFor(
    timeoutMs: Long = 5000,
    message: String = "Condition not met",
    condition: () -> Boolean,
) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (!condition()) {
        if (System.nanoTime() - deadline >= 0) fail<Unit>("Timed out after $timeoutMs ms: $message")
        Thread.sleep(20)
    }
}
