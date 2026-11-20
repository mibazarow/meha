package com.andharness.ru.ui.chats

import android.net.Uri
import com.andharness.ru.data.attachments.AttachmentStore
import com.andharness.ru.domain.chat.AttachmentKind
import com.andharness.ru.domain.chat.AttachmentLimit
import com.andharness.ru.domain.chat.AttachmentPolicy
import com.andharness.ru.domain.chat.PhotoCapture
import com.andharness.ru.domain.chat.PhotoCaptureResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns the composer's attachment staging (§19.2): picked files/images, captured photos and the
 * honest per-attachment notices. It reads and updates the shared [ChatStateHolder]; the actual
 * app-private copying happens later, when the message is sent.
 */
internal class ChatAttachmentController(
    private val state: ChatStateHolder,
    private val scope: CoroutineScope,
    private val attachmentStore: AttachmentStore
) {

    /** §19: reads the picked file's metadata (+ a small thumbnail for images) and stages it. */
    fun add(uri: Uri) {
        if (state.current.isStreaming) return
        scope.launch {
            val info = attachmentStore.describe(uri)
            when (val limit = AttachmentPolicy.validate(state.current.attachments.size, info.size)) {
                is AttachmentLimit.TooMany -> {
                    state.update {
                        it.copy(
                            attachmentNotice = "Можно прикрепить не больше ${limit.max} файлов. " +
                                "Уберите одно из вложений и попробуйте снова."
                        )
                    }
                    return@launch
                }
                is AttachmentLimit.TooLarge -> {
                    state.update {
                        it.copy(
                            attachmentNotice = "Файл «${info.name}» больше " +
                                "${AttachmentPolicy.humanSize(limit.maxBytes)} " +
                                "(сейчас ${AttachmentPolicy.humanSize(info.size)}). " +
                                "Выберите файл меньшего размера."
                        )
                    }
                    return@launch
                }
                AttachmentLimit.Ok -> Unit
            }
            if (state.current.attachments.any { it.uri == uri }) {
                state.update { it.copy(attachmentNotice = "Этот файл уже прикреплён.") }
                return@launch
            }
            val thumb = if (info.kind == AttachmentKind.IMAGE) {
                attachmentStore.loadThumbnail(uri)
            } else {
                null
            }
            state.update {
                it.copy(
                    attachments = it.attachments + PendingAttachment(
                        uri = uri,
                        name = info.name,
                        mime = info.mime,
                        size = info.size,
                        kind = info.kind,
                        thumb = thumb
                    ),
                    attachmentNotice = if (info.kind == AttachmentKind.IMAGE && thumb == null) {
                        "Не удалось загрузить превью «${info.name}» — файл всё равно будет приложен."
                    } else {
                        null
                    }
                )
            }
        }
    }

    fun remove(uri: Uri) {
        state.update { state ->
            state.copy(
                attachments = state.attachments.filterNot { it.uri == uri },
                attachmentNotice = null
            )
        }
    }

    fun clearNotice() {
        state.update { it.copy(attachmentNotice = null) }
    }

    /**
     * §19.2: the composer's «Сделать фото» flow finished. On success the temp capture file is
     * imported into app-private `attachments/` storage (the same path [add] eventually uses for
     * picked images) and staged as an ordinary image attachment; on cancel, or when the import
     * fails, the temp file is deleted and nothing is attached. The size/count limits are the shared
     * [PhotoCapture]/[AttachmentPolicy] ones, so a captured photo behaves like a picked one.
     */
    fun onPhotoCaptured(success: Boolean, captureFile: File) {
        if (state.current.isStreaming) {
            captureFile.delete()
            return
        }
        scope.launch {
            when (
                val result = PhotoCapture.resolve(
                    success = success,
                    fileName = captureFile.name,
                    sizeBytes = captureFile.length(),
                    existingCount = state.current.attachments.size
                )
            ) {
                PhotoCaptureResult.Cancelled -> captureFile.delete()
                is PhotoCaptureResult.Rejected -> {
                    captureFile.delete()
                    state.update { it.copy(attachmentNotice = result.notice) }
                }
                is PhotoCaptureResult.Imported -> {
                    if (state.current.isStreaming) {
                        captureFile.delete()
                        return@launch
                    }
                    val stored = attachmentStore.importCapture(
                        file = captureFile,
                        name = result.name,
                        mime = result.mime,
                        kind = result.kind
                    )
                    captureFile.delete()
                    if (stored == null) {
                        state.update {
                            it.copy(
                                attachmentNotice = "Не удалось приложить фото — файл камеры " +
                                    "не прочитан. Попробуйте снять ещё раз."
                            )
                        }
                        return@launch
                    }
                    val thumb = if (stored.kind == AttachmentKind.IMAGE) {
                        attachmentStore.loadThumbnail(File(stored.path))
                    } else {
                        null
                    }
                    state.update {
                        it.copy(
                            attachments = it.attachments + PendingAttachment(
                                uri = Uri.fromFile(File(stored.path)),
                                name = stored.name,
                                mime = stored.mime,
                                size = File(stored.path).length(),
                                kind = stored.kind,
                                thumb = thumb,
                                stored = stored
                            ),
                            attachmentNotice = if (stored.kind == AttachmentKind.IMAGE && thumb == null) {
                                "Не удалось загрузить превью «${stored.name}» — фото всё равно " +
                                    "будет приложено."
                            } else {
                                null
                            }
                        )
                    }
                }
            }
        }
    }

    /** §19.2: honest notice for the camera flow (permission denied, no camera app, no camera). */
    fun notice(message: String) {
        state.update { it.copy(attachmentNotice = message) }
    }
}
