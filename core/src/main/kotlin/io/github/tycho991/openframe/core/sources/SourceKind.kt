package io.github.tycho991.openframe.core.sources

/**
 * What differs between the kinds of source: how a folder is reached and what removing a source asks of the server.
 * The sync, schedule, status, originals cache and API are the same for all of them.
 */
interface SourceKind {
    /** The ID in `sources.json` and in the API, e.g. `nextcloud-account`. */
    val id: String

    /** The user has to choose a folder before anything syncs (an account); a share names its folder itself. */
    val needsFolder: Boolean

    /** The listing has no folders (a shared album, say): every file is listed whether subfolders are included or not. */
    val listsFlat: Boolean get() = false

    /** The configured folder of [source]. */
    fun remoteFolder(source: StoredSource): RemoteFolder

    /** Asks the server to forget the credentials of [source] when it is removed. Failures are the caller's to ignore. */
    fun revoke(source: StoredSource) {}
}
