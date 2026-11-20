package com.andharness.ru.domain.tools

import com.andharness.ru.domain.agent.LlmTool
import com.andharness.ru.domain.agent.ToolResult

interface ToolRegistry {
    fun definitions(): List<LlmTool>

    /**
     * Language-aware overload of [definitions]. `language` is an [com.andharness.ru.domain.locale.AppLanguage]
     * tag (normalized via [com.andharness.ru.domain.locale.AppLanguage.normalize]); the default
     * implementation ignores it and returns the Russian list, so implementations that do not offer
     * translations are unaffected and `definitions()` stays byte-identical.
     */
    fun definitions(language: String): List<LlmTool> = definitions()

    suspend fun execute(name: String, argumentsJson: String): ToolResult
}
