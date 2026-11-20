package com.andharness.ru.domain.agent

interface ContextManager {
    /**
     * Compiles the conversation for [chatId]. [tools] is the tool catalog that will be sent with
     * the very same request; it is included in the token estimate so the context budget reflects
     * the real payload, not just the message text.
     */
    suspend fun compile(chatId: Long, tools: List<LlmTool> = emptyList()): CompiledContext
}
