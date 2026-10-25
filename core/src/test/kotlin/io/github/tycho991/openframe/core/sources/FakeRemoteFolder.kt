package io.github.tycho991.openframe.core.sources

import java.io.File

/** An in-memory [RemoteFolder] for the tests of the sync logic: no sockets, faults on request. */
class FakeRemoteFolder : RemoteFolder {
    private val content = LinkedHashMap<String, Pair<RemoteFile, ByteArray>>()

    /** The paths downloaded so far, in order. */
    val downloads = ArrayList<String>()

    /** Thrown by `list` while set. */
    @Volatile var listFailure: SourceException? = null

    /** Called before every download; throws to make it fail. */
    @Volatile var beforeDownload: (RemoteFile) -> Unit = {}

    fun put(
        path: String,
        bytes: ByteArray,
        etag: String = "etag-${bytes.size}-${path.hashCode()}",
        modifiedMs: Long = 1_760_000_000_000L,
    ) {
        content[path] = RemoteFile(path, etag, bytes.size.toLong(), modifiedMs) to bytes
    }

    fun remove(path: String) {
        content.remove(path)
    }

    override fun list(recursive: Boolean): List<RemoteFile> {
        listFailure?.let { throw it }
        return content.values.map { it.first }.filter { recursive || '/' !in it.path }
    }

    override fun download(
        file: RemoteFile,
        target: File,
    ) {
        beforeDownload(file)
        val (_, bytes) = content[file.path] ?: throw SourceException(SourceException.Kind.NOT_FOUND, "${file.path} was not found")
        downloads += file.path
        target.writeBytes(bytes)
    }
}

/** A kind of source for the tests of the generic part: each source reads the fake folder that [folders] hands out. */
class FakeKind(
    override val id: String = ID,
    override val needsFolder: Boolean = true,
    override val listsFlat: Boolean = false,
    val folders: (StoredSource) -> RemoteFolder,
) : SourceKind {
    var revoked = 0

    override fun remoteFolder(source: StoredSource): RemoteFolder = folders(source)

    override fun revoke(source: StoredSource) {
        revoked++
    }

    companion object {
        const val ID = "fake"

        /** A source of this kind with the folder `/`, ready to sync. */
        fun source(
            id: String,
            name: String = id,
        ) = StoredSource(id, ID, name, Connection.Share("https://cloud.example.org", "token-$id"), folder = "/")
    }
}
