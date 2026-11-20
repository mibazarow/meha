package com.andharness.ru.data.mcp

import com.andharness.ru.domain.mcp.McpServer

/** The stored server (when written) plus the [McpWriteOutcome] of a write. */
data class McpWriteResult(
    val server: McpServer?,
    val outcome: McpWriteOutcome,
)
