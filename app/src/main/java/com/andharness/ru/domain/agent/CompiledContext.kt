package com.andharness.ru.domain.agent

data class CompiledContext(
    val messages: List<LlmMessage>,
    val estimatedTokens: Long,
    val compressed: Boolean
)
