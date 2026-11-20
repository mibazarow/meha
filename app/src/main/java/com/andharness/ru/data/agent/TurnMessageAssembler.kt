package com.andharness.ru.data.agent

import android.util.Log
import com.andharness.ru.data.attachments.AttachmentStore
import com.andharness.ru.data.vision.VisionCapability
import com.andharness.ru.domain.agent.LlmContentPart
import com.andharness.ru.domain.agent.LlmMessage
import com.andharness.ru.domain.chat.AttachmentKind
import com.andharness.ru.domain.chat.ChatAttachment
import java.io.File
import javax.inject.Inject

/**
 * Assembles the outgoing message list for one agent turn: it attaches the current turn's inline
 * image parts to the last user message AFTER context compilation, so image payloads are never
 * compressed/dropped and never persisted.
 *
 * update.md §3: the inline image is attached ONLY when the configured model is known to accept
 * images (allowlist hit / fresh probe). Otherwise the attachment is still saved to disk and its path
 * is already in the text, so the agent falls back to the on-device `ocr_image` tool.
 */
class TurnMessageAssembler @Inject constructor(
    private val attachmentStore: AttachmentStore,
    private val visionCapability: VisionCapability
) {

    /**
     * Returns [working] with the turn's image parts attached to its last user message (or unchanged
     * when there are no images/last user message). Never mutates the input list.
     */
    suspend fun attachImages(
        working: List<LlmMessage>,
        userContent: String,
        attachments: List<ChatAttachment>
    ): List<LlmMessage> {        val imageAttachments = attachments.filter { it.kind == AttachmentKind.IMAGE }
        // No images → never probe. Images present → ask the capability (allowlist/cache/probe once).
        val visionSupported = imageAttachments.isEmpty() || visionCapability.supportsVision()
        val imageParts = if (visionSupported) {
            imageAttachments.mapNotNull { attachment ->
                attachmentStore.imageContentPart(File(attachment.path))
            }
        } else {
            Log.i(TAG, "Vision недоступен или неизвестен — изображения не шлём инлайн, фолбэк на ocr_image.")
            emptyList()
        }
        val visionFallback = imageAttachments.isNotEmpty() && !visionSupported
        if (imageParts.isEmpty() && !visionFallback) return working
        val index = working.indexOfLast { it.role == "user" }
        if (index < 0) return working
        // The last user message may already carry the compile-time runtime-context tail prepended by
        // ContextManagerImpl; base the multimodal text on the message's OWN content so that tail is
        // preserved. Fall back to [userContent] only when the message text is blank.
        val base = working[index].content?.takeIf { it.isNotBlank() } ?: userContent
        val text = if (visionFallback) {
            "$base\n\n[Изображения не переданы модели: текущая модель не поддерживает Vision. " +
                "Распознай текст на них локально через ocr_image по указанным выше путям.]"
        } else {
            base
        }
        val parts = buildList<LlmContentPart> {
            add(LlmContentPart.Text(text))
            addAll(imageParts)
        }
        return working.toMutableList().also {
            it[index] = it[index].copy(contentParts = parts)
        }
    }

    private companion object {
        private const val TAG = "Andharness"
    }
}
