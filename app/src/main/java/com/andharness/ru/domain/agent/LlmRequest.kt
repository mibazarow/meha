package com.andharness.ru.domain.agent


data class LlmRequest(
    val messages: List<LlmMessage>,
    val tools: List<LlmTool> = emptyList()
)
