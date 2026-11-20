package com.andharness.ru.ui.chats

import com.andharness.ru.domain.agent.ToolActivity
import com.andharness.ru.domain.agent.ToolActivityState
import com.andharness.ru.domain.agent.ToolLabels

/**
 * Resolves the status of the current turn, or null when there is nothing to report. The text is
 * plain (no emoji): the leading glyph is chosen from [ChatStatusKind] in the UI.
 */
internal fun chatStatus(
    contextCompaction: Boolean,
    toolActivity: ToolActivity?,
    isStreaming: Boolean,
    providerThinking: Boolean = false,
    providerSlow: Boolean = false
): ChatStatus? = when {
    contextCompaction -> ChatStatus(
        ChatStatusKind.COMPACTION,
        "Подождите, идёт сжатие контекста."
    )
    toolActivity != null -> when (toolActivity.state) {
        ToolActivityState.RUNNING -> ChatStatus(
            ChatStatusKind.TOOL_RUNNING,
            ToolLabels.label(toolActivity.tool)
        )
        ToolActivityState.SUCCEEDED -> ChatStatus(
            ChatStatusKind.TOOL_SUCCEEDED,
            ToolLabels.label(toolActivity.tool)
        )
        ToolActivityState.FAILED -> ChatStatus(
            ChatStatusKind.TOOL_FAILED,
            "Не удалось: ${ToolLabels.label(toolActivity.tool)}"
        )
    }
    // Reasoning is flowing but its text is deliberately hidden: say that the model is thinking, not
    // that it is typing an answer that has not started yet.
    providerThinking -> ChatStatus(ChatStatusKind.THINKING, "Меха думает…")
    // The provider accepted the request but has produced nothing for a while: the honest status is
    // a hint, not "печатает…", because there is nothing being printed. It clears on the next event.
    providerSlow -> ChatStatus(
        ChatStatusKind.PROVIDER_SLOW,
        "Провайдер долго не отвечает. Проверьте баланс, лимиты и срок действия API-ключа — " +
            "или выберите другого провайдера, либо подождите."
    )
    isStreaming -> ChatStatus(ChatStatusKind.STREAMING, "Меха печатает…")
    else -> null
}

/** Text-only view of [chatStatus] for callers that only need the line. */
internal fun chatStatusText(
    contextCompaction: Boolean,
    toolActivity: ToolActivity?,
    isStreaming: Boolean,
    providerThinking: Boolean = false,
    providerSlow: Boolean = false
): String? = chatStatus(
    contextCompaction,
    toolActivity,
    isStreaming,
    providerThinking,
    providerSlow,
)?.text

/**
 * Optional «использовано X / N» indicator for the last compiled request: [used] estimated tokens
 * against the configured compression [budget]. Null when no run has produced a measurement yet.
 */
internal fun contextUsageText(used: Long?, budget: Long?): String? {
    if (used == null || budget == null || budget <= 0L) return null
    return "Контекст: использовано $used / $budget"
}
