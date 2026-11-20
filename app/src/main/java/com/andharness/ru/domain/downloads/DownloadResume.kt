package com.andharness.ru.domain.downloads

/**
 * Pure HTTP Range helpers for resumable downloads, split out from the download workers so the
 * arithmetic is unit-testable without a network or Android harness.
 *
 * Reference: HTTP range requests — https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Range
 * and the OkHttp "Download a file" streaming pattern — https://square.github.io/okhttp/recipes/
 */
object DownloadResume {

    /** The `Range` header value resuming from [existingBytes], or `null` when a full download is needed. */
    fun rangeHeader(existingBytes: Long): String? =
        if (existingBytes <= 0L) null else "bytes=$existingBytes-"

    /**
     * Resolves the total size of the remote object.
     *
     * Preference order: the `total` of a `Content-Range` header (`bytes 0-1/1117320736`), then
     * `offset + contentLength` for responses that report a length, then [fallback] (the known spec
     * size) when the server reports nothing usable.
     */
    fun resolveTotal(
        contentRange: String?,
        offset: Long,
        contentLength: Long,
        fallback: Long,
    ): Long {
        val fromRange = contentRange?.let { parseContentRangeTotal(it) }
        if (fromRange != null && fromRange > 0L) return fromRange
        if (contentLength > 0L) return offset + contentLength
        return fallback
    }

    /** Parses the `total` part of `bytes <start>-<end>/<total>`; `null` for `*` or a malformed header. */
    fun parseContentRangeTotal(contentRange: String): Long? {
        val slash = contentRange.lastIndexOf('/')
        if (slash < 0 || slash == contentRange.lastIndex) return null
        return contentRange.substring(slash + 1).trim().toLongOrNull()
    }
}
