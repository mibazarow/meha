package com.andharness.ru.domain.chat

/**
 * Pure (JVM-testable) logic behind the composer's «Сделать фото» camera flow (§19.2).
 *
 * Everything here is plain Kotlin — no Android APIs — so the temp-file naming, the
 * "import a captured photo as an attachment" mapping and the cancel path can be unit-tested on the
 * JVM. The Android glue (system camera app via `ActivityResultContracts.TakePicture`, FileProvider
 * content URI, `AttachmentStore.importCapture`) lives in the UI/ViewModel layers and only feeds
 * the values produced here.
 *
 * References:
 * - `ActivityResultContracts.TakePicture` — «An ActivityResultContract to take a picture saving it
 *   into the provided content-Uri», returns `true` if the image was saved:
 *   https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.TakePicture
 * - Runtime CAMERA permission:
 *   https://developer.android.com/training/permissions/requesting
 * - `PackageManager.FEATURE_CAMERA_ANY` (honest "no camera" check):
 *   https://developer.android.com/reference/android/content/pm/PackageManager#FEATURE_CAMERA_ANY
 */

object PhotoCapture {

    /** Prefix of the temp file the system camera app writes into. */
    const val FILE_PREFIX = "meha_capture_"

    /** Photos handed to the model are always JPEG-encoded by the capture intent. */
    const val MIME_JPEG = "image/jpeg"

    private const val EXTENSION = "jpg"

    /**
     * Name of the temp capture file created in the app cache before the camera is launched. Only
     * the extension and prefix are contractual: the camera app writes raw bytes, and the file is
     * deleted again once it has been imported (or on cancel).
     *
     * @param timestampMillis used for uniqueness so two captures never collide.
     */
    fun tempFileName(timestampMillis: Long): String = "$FILE_PREFIX$timestampMillis.$EXTENSION"

    /** Human-readable attachment name shown on the staged tile for a temp capture file. */
    fun displayName(fileName: String): String = "Фото " + fileName.removePrefix(FILE_PREFIX)

    /**
     * Maps a finished capture attempt to what the composer should do with it.
     *
     * A cancel (`success == false`), a missing file name, or a zero-byte file all yield
     * [PhotoCaptureResult.Cancelled] so an empty attachment can never be staged. A real photo is
     * then checked against the same count/size limits as any picked file
     * ([AttachmentPolicy.validate]) and classified through the same [AttachmentPolicy.classify]
     * path, so a captured image is treated exactly like a picked one.
     */
    fun resolve(
        success: Boolean,
        fileName: String?,
        sizeBytes: Long,
        existingCount: Int
    ): PhotoCaptureResult {
        if (!success || fileName.isNullOrBlank() || sizeBytes <= 0L) {
            return PhotoCaptureResult.Cancelled
        }
        return when (val limit = AttachmentPolicy.validate(existingCount, sizeBytes)) {
            is AttachmentLimit.TooMany -> PhotoCaptureResult.Rejected(
                "Можно прикрепить не больше ${limit.max} файлов. " +
                    "Уберите одно из вложений и попробуйте снова."
            )
            is AttachmentLimit.TooLarge -> PhotoCaptureResult.Rejected(
                "Фото больше ${AttachmentPolicy.humanSize(limit.maxBytes)} " +
                    "(сейчас ${AttachmentPolicy.humanSize(sizeBytes)}). " +
                    "Снимите фото меньшего размера."
            )
            AttachmentLimit.Ok -> PhotoCaptureResult.Imported(
                name = displayName(fileName),
                mime = MIME_JPEG,
                kind = AttachmentPolicy.classify(MIME_JPEG, fileName),
                sizeBytes = sizeBytes
            )
        }
    }

    /** True only when the capture must become an attachment; a cancel/empty file adds nothing. */
    fun shouldAttach(result: PhotoCaptureResult): Boolean =
        result is PhotoCaptureResult.Imported
}
