package com.andharness.ru.domain.localserver

/**
 * Lifecycle of the on-device GGUF model download. Pure domain state: it carries no
 * Android/file handle, only the bytes/counters the UI and the downloader need to agree on.
 *
 * - [Idle] — nothing was started in this session and no stored status applies.
 * - [NotDownloaded] — the finished model files are not all on disk (a partial `.part` may still exist
 *   for resume).
 * - [Downloading] — bytes are being written; [downloadedBytes] of [totalBytes] are on disk (the
 *   counters span all required files).
 * - [Verifying] — a byte stream finished and a file is being checksummed/moved into place.
 * - [Done] — the verified model (and its mmproj companion) is at [path]; `LocalLlmServer` can load it.
 * - [Failed] — the download stopped; [reason] is a human-readable Russian explanation.
 */
sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState

    data object NotDownloaded : ModelDownloadState

    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : ModelDownloadState

    data object Verifying : ModelDownloadState

    data class Done(val path: String) : ModelDownloadState

    data class Failed(val reason: String) : ModelDownloadState
}
