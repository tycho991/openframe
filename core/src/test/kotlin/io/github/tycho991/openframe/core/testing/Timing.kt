package io.github.tycho991.openframe.core.testing

/**
 * The fastest of [n] runs of [block] in milliseconds. A GC pause or a busy neighbour on a CI runner hardly slows
 * down all of them, while a real regression slows down every run.
 */
fun bestOfMs(
    n: Int = 3,
    block: () -> Unit,
): Long {
    require(n > 0) { "n must be positive" }
    return (1..n).minOf {
        val start = System.nanoTime()
        block()
        (System.nanoTime() - start) / 1_000_000
    }
}
