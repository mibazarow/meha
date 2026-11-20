package com.andharness.ru.domain.chat

/** Outcome of resolving a finished capture attempt into a chat attachment (§19.2). */
sealed interface PhotoCaptureResult {
    /**
     * The user cancelled the camera, or the capture produced an empty file: nothing must be added.
     */
    data object Cancelled : PhotoCaptureResult

    /** The capture is a usable photo and may be imported with [AttachmentPolicy]'s limits. */
    data class Imported(
        val name: String,
        val mime: String,
        val kind: AttachmentKind,
        val sizeBytes: Long
    ) : PhotoCaptureResult

    /** The photo was taken but must not be attached; [notice] is shown honestly under the input. */
    data class Rejected(val notice: String) : PhotoCaptureResult
}
