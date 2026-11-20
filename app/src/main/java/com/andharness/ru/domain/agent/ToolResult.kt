package com.andharness.ru.domain.agent

/** Result of executing a tool. */
data class ToolResult(
    val success: Boolean,
    val output: String,
    /**
     * Transient multimodal image parts produced by the tool (for example a screenshot). They are
     * never persisted; the orchestrator injects them into the next model call only when the
     * configured model supports vision.
     */
    val imageParts: List<LlmContentPart.ImageUrl> = emptyList(),
    /**
     * Optional structured payload for the chat UI (opaque JSON), persisted on the `tool` row and
     * rendered as a rich preview. Currently the `device_search` result cards
     * ([com.andharness.ru.domain.chat.DeviceSearchPayload]); `null` for tools without a preview.
     * It never enters the model's working history — only [output] does.
     */
    val payload: String? = null,
)
