package io.github.tycho991.openframe.core.testing

import java.io.File

/** Testbilder aus `src/test/resources/fixtures`, erzeugt mit `scripts/generate-test-fixtures.py`. */
object Fixtures {
    fun file(name: String): File = File(requireNotNull(javaClass.getResource("/fixtures/$name")) { "Fixture $name fehlt" }.toURI())

    /** Kopiert die Fixture [name] nach [target] und legt Ordner an. */
    fun copy(
        name: String,
        target: File,
    ): File {
        target.parentFile.mkdirs()
        return file(name).copyTo(target, overwrite = true)
    }
}
