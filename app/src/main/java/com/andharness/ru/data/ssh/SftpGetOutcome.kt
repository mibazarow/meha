package com.andharness.ru.data.ssh

/** Result of [SshClient.get]. */
data class SftpGetOutcome(val remote: String, val localPath: String, val sizeBytes: Long)
