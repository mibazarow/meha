package com.andharness.ru.domain.formatting

/**
 * Compact Russian byte-size formatting for UI copy, e.g. `1,0 ГБ` or `512 МБ`. Pure integer math
 * (no locale) so the output is deterministic and unit-testable. Used by the in-app updater dialogs.
 */
object ByteFormat {

    /** Human-readable byte size: gigabytes with one decimal above 1 GiB, otherwise whole MiB. */
    fun humanBytes(bytes: Long): String {
        val mib = 1024L * 1024L
        val gib = 1024L * mib
        return if (bytes >= gib) {
            val tenths = bytes * 10L / gib
            "${tenths / 10L},${tenths % 10L} ГБ"
        } else {
            "${bytes.coerceAtLeast(0L) / mib} МБ"
        }
    }
}
