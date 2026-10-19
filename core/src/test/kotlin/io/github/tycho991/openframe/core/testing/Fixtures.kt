package io.github.tycho991.openframe.core.testing

import java.io.File

/** Test images from `src/test/resources/fixtures`, generated with `scripts/generate-test-fixtures.py`. */
object Fixtures {
    fun file(name: String): File = File(requireNotNull(javaClass.getResource("/fixtures/$name")) { "Fixture $name is missing" }.toURI())

    /** Copies the fixture [name] to [target] and creates folders. */
    fun copy(
        name: String,
        target: File,
    ): File {
        target.parentFile.mkdirs()
        return file(name).copyTo(target, overwrite = true)
    }
}
