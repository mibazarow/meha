package com.andharness.ru.domain.agent


/**
 * The tool the agent is working with right now, for the chat status indicator (§17).
 * Holds only the tool NAME — never arguments or other sensitive data.
 */
data class ToolActivity(
    val tool: String,
    val state: ToolActivityState
)
