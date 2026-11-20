package com.andharness.ru.domain.tools

import com.andharness.ru.domain.agent.ToolResult
import kotlinx.serialization.json.JsonObject

/** Executes one tool invocation (arguments is a parsed JSON object). */
interface ToolHandler {
    val name: String
    suspend fun execute(arguments: JsonObject): ToolResult
}
