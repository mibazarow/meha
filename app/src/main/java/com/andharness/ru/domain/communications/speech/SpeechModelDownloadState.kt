package com.andharness.ru.domain.communications.speech

/**
 * Lifecycle of a speech-model download ([SpeechModel]). Pure domain state: it carries no Android or
 * file handle, only the model id and the bytes/counters the UI and the downloader agree on.
 *
 * Mirrors `com.andharness.ru.domain.localserver.ModelDownloadState` for the multi-model speech case:
 * each progress state is tagged with the [SpeechModel.id] it belongs to, so a UI can tell which model
 * the counters are for.
 *
 * - [Idle] — nothing was started in this session and no stored status applies.
 * - [NotDownloaded] — no finished model file is on disk (a partial `.part` may still exist for resume).
 * - [Downloading] — bytes are being written; [downloadedBytes] of [totalBytes] are on disk.
 * - [Verifying] — the byte stream finished and the file is being checksummed/moved into place.
 * - [Done] — the verified model is at [path]; `WhisperTranscriber` can load it.
 * - [Failed] — the download stopped; [reason] is a human-readable Russian explanation.
 */
sealed interface SpeechModelDownloadState {

    data object Idle : SpeechModelDownloadState

    data object NotDownloaded : SpeechModelDownloadState

    data class Downloading(
        val modelId: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : SpeechModelDownloadState

    data class Verifying(val modelId: String) : SpeechModelDownloadState

    data class Done(
        val modelId: String,
        val path: String,
    ) : SpeechModelDownloadState

    data class Failed(
        val modelId: String,
        val reason: String,
    ) : SpeechModelDownloadState
}
