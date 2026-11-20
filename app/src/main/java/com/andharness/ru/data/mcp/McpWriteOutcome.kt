package com.andharness.ru.data.mcp

/** Outcome of an [McpServerStore.put] call, so `mcp_server_add` can report exactly what happened. */
enum class McpWriteOutcome {
    CREATED,
    UPDATED,
    INVALID,
    NOT_FOUND,
    LIMIT,
}
