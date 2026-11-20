package com.andharness.ru.data.tools.system

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.settings.LlmSettings
import com.andharness.ru.domain.settings.LlmSettingsStore
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import com.andharness.ru.data.tools.str

/**
 * Lets the agent read and change its own LLM configuration from chat: [settings_get], [self_config].
 * The API key is never returned.
 */
class SelfConfigToolsHandler(
    private val settingsStore: LlmSettingsStore,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "settings_get"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val s = settingsStore.config.first()
                    ToolResult(
                        true,
                        "endpoint=${s.endpoint}\nmodel=${s.model}\nreasoning=${s.reasoningEffort}"
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "self_config"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val model = arguments.str("model")
                    val endpoint = arguments.str("endpoint")
                    val reasoning = arguments.str("reasoning")?.lowercase()
                    if (reasoning != null && reasoning !in LlmSettings.REASONING_EFFORTS) {
                        return@withContext ToolResult(
                            false,
                            "reasoning: допустимо ${LlmSettings.REASONING_EFFORTS.joinToString("/")}"
                        )
                    }
                    if (model == null && endpoint == null && reasoning == null) {
                        return@withContext ToolResult(false, "Укажите хотя бы одно из: model, endpoint, reasoning.")
                    }
                    val changed = ArrayList<String>()
                    if (endpoint != null) {
                        settingsStore.updateEndpoint(endpoint)
                        changed.add("endpoint=$endpoint")
                    }
                    if (model != null) {
                        settingsStore.updateModel(model)
                        changed.add("model=$model")
                    }
                    if (reasoning != null) {
                        settingsStore.updateReasoningEffort(reasoning)
                        changed.add("reasoning=$reasoning")
                    }
                    ToolResult(true, "Настройки обновлены: ${changed.joinToString(", ")}")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
    )

}
