package io.github.tycho991.openframe.core

/** Invalid input with one error entry per field, e.g. `clock.sizePercent`. Becomes 400. */
class ValidationException(
    val fields: Map<String, String>,
) : RuntimeException("Invalid values: ${fields.keys.joinToString()}")
