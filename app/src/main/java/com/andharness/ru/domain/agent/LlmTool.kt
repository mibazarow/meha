package com.andharness.ru.domain.agent

import kotlinx.serialization.json.JsonObject

/** A tool (function) definition exposed to the LLM. */
data class LlmTool(
    val name: String,
    val description: String,
    val parameters: JsonObject
)
