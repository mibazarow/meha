package com.andharness.ru.data.ssh

/** Result of [SshClient.exec]: both streams, the exit code and whether it ran out of time. */
data class SshExecOutcome(
    val stdout: String,
    val stderr: String,
    val exitStatus: Int,
    val timedOut: Boolean,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean
)
