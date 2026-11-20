package com.andharness.ru.data.ssh

/** Result of [SshClient.put]. */
data class SftpPutOutcome(val localPath: String, val remote: String, val sizeBytes: Long)
