package com.andharness.ru.data.ssh

/** One directory entry of an SFTP listing. */
data class SftpEntry(
    val name: String,
    val directory: Boolean,
    val sizeBytes: Long,
    val permissions: String
)
