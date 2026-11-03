package io.github.tycho991.openframe.core.store

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * A file that is replaced atomically: first `name.tmp`, then `fsync`, then `rename`. A power loss thus leaves either
 * the old or the new content, never half a file.
 */
class AtomicFile(
    val file: File,
) {
    val tmp = File(file.parentFile, file.name + ".tmp")

    /** Writes [bytes]; [beforeReplace] runs after the temporary file is complete and before it replaces [file]. */
    fun write(
        bytes: ByteArray,
        beforeReplace: () -> Unit = {},
    ) {
        file.parentFile?.mkdirs()
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        beforeReplace()
        // rename(2) replaces the target file atomically. java.nio.file exists on Android only from API 26.
        if (!tmp.renameTo(file)) throw IOException("${tmp.name} could not be renamed to ${file.name}")
    }

    fun writeText(
        text: String,
        beforeReplace: () -> Unit = {},
    ) = write(text.toByteArray(Charsets.UTF_8), beforeReplace)

    /** The content, or `null` if the file does not exist. Throws [IOException] if it cannot be read. */
    fun readText(): String? = if (file.exists()) file.readText() else null
}
