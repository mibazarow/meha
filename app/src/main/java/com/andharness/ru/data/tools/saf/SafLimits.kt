package com.andharness.ru.data.tools.saf

/** Shared size limits of the SAF tools. */
internal object SafLimits {
    const val MAX_READ_BYTES = 1_048_576

    /** Upper bound for a caller-supplied `saf_read` limit (64 MiB). */
    const val MAX_SDK_READ_LIMIT = 64 * 1024 * 1024

    const val BINARY_PROBE_BYTES = 8 * 1024

    /** Cap for the content returned by `saf_open_document` (64 KiB). */
    const val MAX_OPEN_BYTES = 64 * 1024
}
