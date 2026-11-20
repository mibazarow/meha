package com.andharness.ru.data.tools.trust

import android.content.Context
import com.andharness.ru.data.trust.TrustedSourceRules
import com.andharness.ru.data.trust.TrustedSourceStore
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.data.tools.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Builds the three allowlist tools of the prompt-injection defense:
 *
 * - `trusted_source_add` — remembers a source whose external text may command the agent;
 * - `trusted_source_list` — honestly shows the current allowlist;
 * - `trusted_source_remove` — revokes a previously trusted source.
 *
 * All three are non-dangerous and locally persisted (SharedPreferences + JSON). They only manage the
 * allowlist; the actual security rule lives in the system prompt (see `ContextManagerImpl`).
 */
class TrustedSourceToolsHandler(
    context: Context,
) {

    private val store = TrustedSourceStore(context)

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "trusted_source_add"
            override suspend fun execute(arguments: JsonObject): ToolResult =
                withContext(Dispatchers.IO) {
                    try {
                        val type = arguments.str("type").orEmpty()
                        val value = arguments.str("value")
                            ?: return@withContext ToolResult(
                                false,
                                "Укажите value — сам источник (номер для sms, адрес для email, " +
                                    "имя пакета для notification, домен для web)."
                            )
                        val normalizedType = TrustedSourceRules.normalizeType(type)
                        val normalizedValue = TrustedSourceRules.normalizeValue(type, value)
                            ?: return@withContext ToolResult(
                                false,
                                "Некорректное значение источника: «$value»."
                            )
                        if (store.add(type, value)) {
                            ToolResult(
                                true,
                                "Доверенный источник добавлен: $normalizedType $normalizedValue. " +
                                    "Команды из его текста можно выполнять без подтверждения."
                            )
                        } else {
                            ToolResult(
                                true,
                                "Этот источник уже в списке доверенных: " +
                                    "$normalizedType $normalizedValue."
                            )
                        }
                    } catch (e: Exception) {
                        ToolResult(false, "Ошибка: ${e.message}")
                    }
                }
        },
        object : ToolHandler {
            override val name = "trusted_source_list"
            override suspend fun execute(arguments: JsonObject): ToolResult =
                withContext(Dispatchers.IO) {
                    try {
                        val sources = store.list()
                        if (sources.isEmpty()) {
                            ToolResult(true, "Доверенных источников нет.")
                        } else {
                            val lines = sources.joinToString("\n") { "- ${it.type} ${it.value}" }
                            ToolResult(true, "Доверенные источники (${sources.size}):\n$lines")
                        }
                    } catch (e: Exception) {
                        ToolResult(false, "Ошибка: ${e.message}")
                    }
                }
        },
        object : ToolHandler {
            override val name = "trusted_source_remove"
            override suspend fun execute(arguments: JsonObject): ToolResult =
                withContext(Dispatchers.IO) {
                    try {
                        val type = arguments.str("type").orEmpty()
                        val value = arguments.str("value")
                            ?: return@withContext ToolResult(
                                false,
                                "Укажите value — источник, который нужно убрать из доверенных."
                            )
                        if (store.remove(type, value)) {
                            val normalizedValue =
                                TrustedSourceRules.normalizeValue(type, value) ?: value
                            ToolResult(
                                true,
                                "Источник удалён из доверенных: " +
                                    "${TrustedSourceRules.normalizeType(type)} $normalizedValue. " +
                                    "Команды из его текста снова требуют подтверждения."
                            )
                        } else {
                            ToolResult(false, "Такого источника нет в списке доверенных.")
                        }
                    } catch (e: Exception) {
                        ToolResult(false, "Ошибка: ${e.message}")
                    }
                }
        },
    )
}
