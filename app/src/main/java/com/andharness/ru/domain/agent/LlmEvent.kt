package com.andharness.ru.domain.agent


/** Streamed events produced by a completion request. */
sealed interface LlmEvent {
    data class Text(val delta: String) : LlmEvent

    /**
     * The model started emitting INTERNAL reasoning (e.g. `reasoning_content`). The reasoning text
     * itself is never emitted — this signal only lets the UI switch «Меха печатает…» to
     * «Меха думает…» and prove the provider is still responsive.
     */
    data object Reasoning : LlmEvent
    data class ToolCalls(val calls: List<LlmToolCall>) : LlmEvent
    data object Done : LlmEvent

    /**
     * The provider's own token accounting for the request that just ran (`usage.prompt_tokens` /
     * `usage.completion_tokens`). Providers send it as the final chunk, often with an empty
     * `choices` array. It never changes the answer; it exists so the local prompt-size estimate can
     * be checked against reality instead of being trusted on intuition.
     */
    data class Usage(val promptTokens: Long, val completionTokens: Long = 0L) : LlmEvent

    data class Error(val message: String, val retryable: Boolean) : LlmEvent
}
