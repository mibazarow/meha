package com.andharness.ru.domain.agent

/**
 * One part of a multimodal message content array (OpenAI-compatible).
 *
 * A plain text-only message keeps using [LlmMessage.content]; when [LlmMessage.contentParts] is
 * present it is serialised as a content ARRAY instead, which is what a multimodal model needs to
 * actually "see" an attached image (§19.3).
 *
 * Reference: https://platform.openai.com/docs/guides/vision (content parts: `text`, `image_url`)
 */
sealed interface LlmContentPart {
    data class Text(val text: String) : LlmContentPart

    /** `image_url` part carrying an inline `data:` URI (base64) so no external host is needed. */
    data class ImageUrl(val dataUri: String) : LlmContentPart
}
