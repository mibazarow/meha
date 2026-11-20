package com.andharness.ru.data.ssh


/**
 * A failed SSH operation, with enough structure for the tools to answer honestly.
 *
 * @param hostKey the key the server actually presented — present (and worth showing to the user) for
 *   [Kind.HOST_KEY_UNKNOWN] and [Kind.HOST_KEY_CHANGED], absent otherwise.
 */
class SshFailure(
    message: String,
    val kind: Kind,
    val hostKey: HostKeyFormat.Info? = null
) : Exception(message) {
    enum class Kind {
        /** TCP/handshake/protocol level failure. */
        CONNECTION,

        /** The server rejected the credentials. */
        AUTH,

        /** This host has never been trusted: the user must confirm the fingerprint first. */
        HOST_KEY_UNKNOWN,

        /** The presented key differs from the remembered one — refuse (possible MITM). */
        HOST_KEY_CHANGED,

        /** The password/passphrase/key reference is missing or unreadable. */
        SECRET_MISSING,

        /** A local file problem (missing upload source, unwritable download target). */
        LOCAL_IO,

        /** The operation exceeded its deadline. */
        TIMEOUT,

        /** The operation was refused by policy (e.g. overwrite without `overwrite=true`). */
        REFUSED,

        /** SFTP/protocol error reported by the server. */
        PROTOCOL
    }
}
