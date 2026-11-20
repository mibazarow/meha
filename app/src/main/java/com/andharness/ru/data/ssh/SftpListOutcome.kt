package com.andharness.ru.data.ssh

/** Result of [SshClient.list]. */
data class SftpListOutcome(
    val path: String,
    val entries: List<SftpEntry>,
    val truncated: Boolean
)
