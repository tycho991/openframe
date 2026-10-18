package io.github.tycho991.openframe.core

/** Ungültige Eingabe mit einer Fehlerangabe pro Feld, z. B. `clock.sizePercent`. Wird zu 400. */
class ValidationException(
    val fields: Map<String, String>,
) : RuntimeException("Ungültige Werte: ${fields.keys.joinToString()}")
