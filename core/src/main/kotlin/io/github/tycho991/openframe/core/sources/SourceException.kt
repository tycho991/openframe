package io.github.tycho991.openframe.core.sources

/**
 * A failure while talking to the server of a source. The message is for the status and the log: it names the
 * server and what went wrong, never a credential, a share token or a request header.
 */
class SourceException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
    /** For [Kind.THROTTLED]: how long the server asked to wait, already clamped; `null` if it did not say. */
    val retryAfterMs: Long? = null,
    /**
     * The failure concerns one file, not the folder: a download that the server refused. Such a refusal does not put the
     * source into the state of rejected access (spec `remote-sources`, "Rejected access" › One file refused).
     */
    val fileOnly: Boolean = false,
) : Exception(message, cause) {
    /** The same failure, marked as one of a single file. */
    fun ofFile(): SourceException = if (fileOnly) this else SourceException(kind, message.orEmpty(), cause, retryAfterMs, fileOnly = true)

    enum class Kind {
        /** No connection: the server is down, the name unknown, the network off, or an answer that is too slow. */
        UNREACHABLE,

        /** The certificate of the server is not trusted. */
        TLS,

        /** A TLS connection could not be set up for another reason than the certificate, such as no common protocol. */
        TLS_HANDSHAKE,

        /** The server refused the credentials or the password of a share. */
        UNAUTHORIZED,

        /** The folder or the file is not there. */
        NOT_FOUND,

        /** The source itself is not there any more, such as a share that was withdrawn: nothing of it can be read. */
        GONE,

        /** The source is there but does not allow reading, such as a share that only accepts uploads. */
        NOT_READABLE,

        /** The server answered with an error. */
        SERVER,

        /** The server asks to wait (HTTP 429). */
        THROTTLED,

        /** The answer was not what the server of this kind of source sends. */
        PROTOCOL,

        /** An answer went beyond a fixed limit (spec `nextcloud-source`, "Bounded answers of a server"). */
        LIMIT,

        /** A file was replaced on the server between the listing and its download. */
        CHANGED,

        /** The login flow pointed to an address that would leave `https` or the server (spec "No downgrade during the login"). */
        INSECURE_LOGIN,

        /** The login was not completed. */
        LOGIN_FAILED,

        /** Not enough free storage on the frame. */
        STORAGE,
        ;

        /** The value of the contract's `SourceErrorCode`. */
        val code: String get() = name.lowercase()
    }
}

/**
 * The values of the contract's `SourceErrorCode`: every [SourceException.Kind], [UNEXPECTED] for any other failure and
 * [TOO_MANY_SOURCES] for a login on a full frame. They are stored as strings, so that a stored code a later or earlier
 * version does not know never makes the file unreadable; such a code is served as `null`.
 */
object SourceErrorCodes {
    const val UNEXPECTED = "unexpected"
    const val TOO_MANY_SOURCES = "too_many_sources"

    val ALL: Set<String> = SourceException.Kind.entries.mapTo(LinkedHashSet()) { it.code } + UNEXPECTED + TOO_MANY_SOURCES

    /** The kinds that put a source into the state of rejected access when listing its folder fails with them. */
    val REJECTED: Set<SourceException.Kind> =
        setOf(SourceException.Kind.UNAUTHORIZED, SourceException.Kind.GONE, SourceException.Kind.NOT_READABLE)

    /** [code] if the contract knows it, else `null`. */
    fun served(code: String?): String? = code?.takeIf { it in ALL }

    /** The code of any failure of a sync. */
    fun of(e: Throwable): String = (e as? SourceException)?.kind?.code ?: UNEXPECTED
}
