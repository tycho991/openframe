package io.github.tycho991.openframe.core.sources

/**
 * A failure while talking to the server of a source. The message is for the status and the log: it names the
 * server and what went wrong, never a credential, a share token or a request header.
 */
class SourceException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind {
        /** No connection: the server is down, the name unknown, the network off. */
        UNREACHABLE,

        /** The certificate of the server is not trusted. */
        TLS,

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

        /** The answer was not what the server of this kind of source sends. */
        PROTOCOL,

        /** The login was not completed. */
        LOGIN_FAILED,

        /** Not enough free storage on the frame. */
        STORAGE,
    }
}
