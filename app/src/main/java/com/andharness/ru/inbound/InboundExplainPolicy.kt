package com.andharness.ru.inbound

import com.andharness.ru.domain.chat.AttachmentKind
import com.andharness.ru.domain.chat.ChatAttachment

/**
 * Pure, Android-free text policy for the «Что это?» / «Спросить Меху» inbound entry point.
 *
 * The share sheet and the text-selection toolbar deliver text and/or an image; this object turns
 * that payload into the exact composer / user-message text that starts the chat. Keeping it here
 * (no `Intent`, no `Context`) means the mapping is unit-tested on the JVM, while
 * `InboundIntentHandler` only does the Android work (reading the Intent, copying attachments).
 *
 * Honest limits:
 * - For a shared image the message simply asks the model to look at it; whether the image is sent
 *   inline or read with the on-device `ocr_image` fallback is decided later by the agent
 *   (`AgentOrchestratorImpl`), which never fakes Vision support. This object therefore never
 *   promises that the model can "see" the picture.
 * - When the payload carries no usable text, only an image, only a generic instruction is seeded —
 *   never an empty auto-sent message.
 */
object InboundExplainPolicy {

    /** Leading question for a text-only explain. */
    const val TEXT_QUESTION = "Объясни, что это значит:"

    /** Instruction for an image-only explain (no accompanying text). */
    const val IMAGE_QUESTION =
        "Что изображено на этой картинке? Объясни подробно, что здесь происходит."

    /** Instruction when both an image and text were shared. */
    const val IMAGE_WITH_TEXT_QUESTION =
        "Объясни, что это значит. Учти и изображение, и текст:"

    /** Instruction for a non-image attachment (e.g. a shared document). */
    const val ATTACHMENT_QUESTION =
        "Изучи вложение и объясни, что в нём важного."

    /**
     * Builds the seeded message. [text] is the shared / selected text (may be blank), [attachments]
     * are the files that were successfully copied into app-private storage.
     *
     * The shared text is quoted (`> ` block) so a long pasted block stays visually separated from
     * the instruction; the instruction always comes first so the composer shows the user what
     * Меха is about to do.
     */
    fun buildSeedText(text: String, attachments: List<ChatAttachment>): String {
        val hasImage = attachments.any { it.kind == AttachmentKind.IMAGE }
        val quoted = quote(text)
        return when {
            hasImage && quoted != null -> "$IMAGE_WITH_TEXT_QUESTION\n\n$quoted"
            hasImage -> IMAGE_QUESTION
            quoted != null -> "$TEXT_QUESTION\n\n$quoted"
            attachments.isNotEmpty() -> ATTACHMENT_QUESTION
            else -> ""
        }
    }

    /**
     * `> line` blockquote of [text], or null when there is nothing to quote. Blank lines become a
     * bare `>` so the quote block stays intact.
     */
    fun quote(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.lineSequence().joinToString("\n") { line ->
            if (line.isEmpty()) ">" else "> $line"
        }
    }

    /**
     * Temporary chat title for an explain chat; the model renames it from the first message, so it
     * only has to be recognisable in the chat list until then.
     */
    fun title(attachments: List<ChatAttachment>): String =
        if (attachments.any { it.kind == AttachmentKind.IMAGE }) "Что это? — изображение"
        else "Что это?"
}
