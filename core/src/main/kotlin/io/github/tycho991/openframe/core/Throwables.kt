package io.github.tycho991.openframe.core

/** [this] and its causes, innermost last; a cause that is its own cause ends the chain. */
fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause.takeIf { c -> c !== it } }
