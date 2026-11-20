package com.andharness.ru.domain.tools

import com.andharness.ru.domain.agent.LlmTool
import kotlinx.serialization.json.JsonObject

/** Metadata + parameters schema for a single tool. */
data class Tool(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val needsPermission: String? = null,
    val dangerous: Boolean = false
) {
    fun toLlmTool(): LlmTool = LlmTool(name = name, description = description, parameters = parameters)

    companion object {
        const val PERMISSION_REQUIRED = "PERMISSION_REQUIRED"
    }
}
