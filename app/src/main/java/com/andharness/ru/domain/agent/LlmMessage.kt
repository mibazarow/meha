package com.andharness.ru.domain.agent

data class LlmMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<LlmToolCall>? = null,
    val toolCallId: String? = null,
    /** Multimodal content parts; when non-null, serialised as a content array instead of a string. */
    val contentParts: List<LlmContentPart>? = null
)
