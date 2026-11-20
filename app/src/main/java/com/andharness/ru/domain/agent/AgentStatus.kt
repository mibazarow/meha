package com.andharness.ru.domain.agent


data class AgentStatus(
    val state: AgentState = AgentState.IDLE,
    val chatId: Long? = null,
    val isStreaming: Boolean = false,
    val toolLog: String? = null,
    val pendingPermission: String? = null,
    val toolActivity: ToolActivity? = null,
    /**
     * In-flight assistant text of the current run. Tools persist text to the database on a throttle,
     * so the UI overlays this value on the streaming message to keep the visible answer in sync
     * between writes (`null` when no run is streaming).
     */
    val streamingMessageId: Long? = null,
    val streamingText: String? = null,
    /**
     * True while the current request is being prepared with a compacted context. Transient,
     * in-memory only: it drives the «Подождите, идёт сжатие контекста.» status line and is never
     * persisted as a chat message.
     */
    val contextCompaction: Boolean = false,
    /**
     * True while the LLM request has produced NO event for longer than the slow-provider threshold.
     * Drives a transient hint («Провайдер долго не отвечает — проверьте баланс и лимиты») in the
     * status line; it is cleared as soon as any event arrives and is never persisted.
     */
    val providerSlow: Boolean = false,
    /**
     * True while the provider streams INTERNAL reasoning (`reasoning_content`). Drives
     * «Меха думает…» instead of «Меха печатает…»; the reasoning text is never shown.
     */
    val providerThinking: Boolean = false,
    /**
     * Estimated size of the LAST compiled request (messages + tool schemas) and the compression
     * budget it was measured against, for the optional «использовано X / N» chat indicator.
     * Display-only; measured at compile time on the request actually sent.
     */
    val contextUsedTokens: Long? = null,
    val contextBudgetTokens: Long? = null
)
