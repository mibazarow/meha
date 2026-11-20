package com.andharness.ru.domain.agent

import kotlinx.coroutines.flow.Flow

/**
 * OpenAI-compatible streaming interface for the assistant model.
 * Emits text deltas and tool calls as they stream, then [LlmEvent.Done].
 */
interface LlmClient {
    fun complete(request: LlmRequest): Flow<LlmEvent>
}
