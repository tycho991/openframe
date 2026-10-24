package io.github.tycho991.openframe.core.nextcloud

/**
 * A failure while talking to the Nextcloud server. The message is for the status and the log: it names the
 * server and what went wrong, never a credential or a request header.
 */
class NextcloudException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind {
        /** No connection: the server is down, the name unknown, the network off. */
        UNREACHABLE,

        /** The certificate of the server is not trusted. */
        TLS,

        /** The server refused the credentials. */
        UNAUTHORIZED,

        /** The folder or the file is not there. */
        NOT_FOUND,

        /** The server answered with an error. */
        SERVER,

        /** The answer was not what a Nextcloud server sends. */
        PROTOCOL,

        /** The login was not completed. */
        LOGIN_FAILED,

        /** Not enough free storage on the frame. */
        STORAGE,
    }
}
