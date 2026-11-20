package com.andharness.ru.data.ssh

import com.andharness.ru.domain.ssh.SshServer

/**
 * Server disambiguation for the SSH tools (update.md §5.4): when a server is not named, or the name
 * matches more than one registration, the tools must show a numbered list and ask — never pick for
 * the user.
 *
 * Pure (no Android/JSch) so every branch is unit-tested.
 */
sealed interface ServerResolution {
    /** Exactly one server matched. */
    data class Found(val server: SshServer) : ServerResolution

    /** Several candidates: the caller lists them and asks which one. */
    data class Ambiguous(val candidates: List<SshServer>) : ServerResolution

    /** No `name`/`server_id` was given: the caller shows the full numbered list and asks. */
    data object Unspecified : ServerResolution

    /** A name/id was given but nothing matched. */
    data object NotFound : ServerResolution
}
