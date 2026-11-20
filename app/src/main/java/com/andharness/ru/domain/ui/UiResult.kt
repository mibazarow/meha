package com.andharness.ru.domain.ui


sealed interface UiResult {
    /** Picker/capture returned a Uri (may be null if the contract allows it). */
    data class UriResult(val uri: android.net.Uri?) : UiResult

    /** MediaStore consent dialog outcome. */
    data class ConsentResult(val granted: Boolean) : UiResult

    /**
     * Raw Activity-result outcome (resultCode + data) of an interactive flow, e.g. screen-capture
     * consent. `data` is the `Intent` returned by the system consent dialog and must be handed to
     * `MediaProjectionManager.getMediaProjection(resultCode, data)` unchanged.
     */
    data class ActivityResult(val resultCode: Int, val data: android.content.Intent?) : UiResult

    /**
     * The UI successfully handed the job to the system: for [PrintRequest] this means
     * `PrintManager.print()` returned and the system print dialog was shown. It never means the
     * document was printed — the user still has to confirm in that dialog.
     */
    data object PrintDialogShown : UiResult
    data object Cancelled : UiResult
    data class Failed(val message: String) : UiResult
}
