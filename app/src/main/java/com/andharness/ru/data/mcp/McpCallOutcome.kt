package com.andharness.ru.data.mcp

import com.andharness.ru.domain.mcp.McpToolCallResult

/** Outcome of one remote `tools/call`. */
sealed interface McpCallOutcome {
    data class Ok(val result: McpToolCallResult) : McpCallOutcome
    data class Error(val message: String) : McpCallOutcome
}
