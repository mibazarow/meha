package com.andharness.ru.domain.localserver

/**
 * Pure, Android-free resolution of the honest on-device model [ModelDownloadState] from
 * (a) whether the required FILES are really on disk and (b) the persisted status flag.
 *
 * The persisted `done` flag is a HINT ONLY: it is trusted solely when every required file is present
 * at its exact expected size. If the files were removed outside the app (or truncated), a stale
 * `done` must NOT be shown — the honest state is [ModelDownloadState.NotDownloaded] so the UI offers
 * «Скачать модель» again instead of falsely claiming «Модель загружена и проверена».
 *
 * Kept pure JVM so the rule "prefs=done but files missing → NotDownloaded" is unit-testable without
 * Android (`LocalModelStateResolverTest`).
 */
object LocalModelStateResolver {

    /** Persisted status flag: the model was downloaded, verified and moved into place. */
    const val STATUS_DONE = "done"

    /** Persisted status flag: the last download attempt failed; [ModelDownloadState.Failed] carries why. */
    const val STATUS_FAILED = "failed"

    /**
     * @param filesComplete true when EVERY required artifact exists at its exact expected size.
     * @param storedStatus the persisted flag ([STATUS_DONE], [STATUS_FAILED], another value or `null`).
     * @param storedReason the persisted human-readable failure reason, if any.
     * @param donePath the absolute model path reported when the files are really present.
     */
    fun resolve(
        filesComplete: Boolean,
        storedStatus: String?,
        storedReason: String?,
        donePath: String,
    ): ModelDownloadState = when {
        // Files on disk win over every persisted hint.
        filesComplete -> ModelDownloadState.Done(donePath)
        storedStatus == STATUS_FAILED -> ModelDownloadState.Failed(storedReason.orEmpty())
        // A stale `done` (and any other flag) is distrusted without the files on disk.
        else -> ModelDownloadState.NotDownloaded
    }
}
