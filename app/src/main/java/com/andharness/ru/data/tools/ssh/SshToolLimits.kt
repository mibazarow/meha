package com.andharness.ru.data.tools.ssh

/** Shared numeric limits of the SSH tools (ports, timeouts, list/log caps, output budget). */
internal object SshToolLimits {
    const val DEFAULT_PORT = 22
    const val MAX_PORT = 65535

    const val DEFAULT_EXEC_TIMEOUT_SEC = 60
    const val DEFAULT_SFTP_TIMEOUT_SEC = 60
    const val MAX_EXEC_TIMEOUT_SEC = 300

    const val DEFAULT_LIST_LIMIT = 200
    const val MAX_LIST_LIMIT = 1000

    const val MAX_OUTPUT_CHARS = 24_000

    /** Coding bridge: default/cap for `repo_log` and the `repo_run` millisecond budget. */
    const val DEFAULT_LOG_LIMIT = 20
    const val MAX_LOG_LIMIT = 100

    const val DEFAULT_RUN_TIMEOUT_MS = 60_000L
    const val MIN_RUN_TIMEOUT_MS = 1_000L
    const val MAX_RUN_TIMEOUT_MS = MAX_EXEC_TIMEOUT_SEC * 1000L

    /** Short, bounded budget for the best-effort removal of the temporary patch file. */
    const val CLEANUP_TIMEOUT_SEC = 15
}
