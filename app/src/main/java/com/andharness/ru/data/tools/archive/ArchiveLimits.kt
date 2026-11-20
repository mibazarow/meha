package com.andharness.ru.data.tools.archive

/**
 * Bounds for archive extraction, shared by the TAR / 7z / RAR extract tools: an entry-count cap and
 * a total-uncompressed-size cap, so a crafted archive ("archive bomb") cannot exhaust the device.
 */
internal object ArchiveLimits {

    // TAR extraction limits: bound entry count and total uncompressed size against tar bombs.
    const val MAX_TAR_ENTRIES = 10_000
    const val MAX_TAR_UNCOMPRESSED_BYTES = 2L * 1024L * 1024L * 1024L

    // 7z extraction limits: same bound (entry count + total uncompressed size).
    const val MAX_7Z_ENTRIES = 10_000
    const val MAX_7Z_UNCOMPRESSED_BYTES = 2L * 1024L * 1024L * 1024L

    // RAR extraction limits: same bound (entry count + declared uncompressed size).
    const val MAX_RAR_ENTRIES = 10_000
    const val MAX_RAR_UNCOMPRESSED_BYTES = 2L * 1024L * 1024L * 1024L

    const val COPY_BUFFER_SIZE = 8 * 1024
}
