package com.andharness.ru.data.tools.memory

import com.andharness.ru.data.memory.DeviceFactStore
import com.andharness.ru.domain.memory.DeviceFact
import com.andharness.ru.domain.memory.DeviceFactSpecs
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.andharness.ru.data.tools.formatTimestamp

/**
 * Builds the device-fact tools (update.md §12 / TZ §12.3):
 *
 * - `device_facts_list`    — what is cached and how old it is;
 * - `device_facts_get`     — one fact, re-collected on the spot when stale;
 * - `device_facts_refresh` — re-collect one fact or all cacheable facts;
 * - `device_facts_forget`  — drop one/all facts and journal the reason (`MANUAL`).
 *
 * All four are honest: a fact that cannot be produced right now (missing permission, no hardware)
 * is reported as unavailable instead of being invented.
 */
class DeviceFactsToolsHandler(
    private val store: DeviceFactStore
) {

    private fun now(): Long = System.currentTimeMillis()

    private fun time(at: Long): String = formatTimestamp(at, "yyyy-MM-dd HH:mm")

    private fun keyOf(arguments: JsonObject): String =
        (arguments["key"] as? JsonPrimitive)?.content?.trim().orEmpty()

    private fun knownKeys(): String = DeviceFactSpecs.keys.joinToString(", ")

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "device_facts_list"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val facts = store.all().sortedBy { it.key }
                    if (facts.isEmpty()) {
                        return@withContext ToolResult(
                            true,
                            "Кэш фактов об устройстве пуст. Вызови device_facts_refresh, чтобы собрать факты."
                        )
                    }
                    val at = now()
                    val lines = facts.map { fact ->
                        val fresh = if (DeviceFactSpecs.isFresh(fact, at)) "свежий" else "устарел"
                        "• ${fact.key} [${fact.category}] — $fresh, обновлён ${time(fact.updatedAt)}, " +
                            "размер ${fact.valueJson.length} Б"
                    }
                    ToolResult(
                        true,
                        "Факты об устройстве (${facts.size}):\n" + lines.joinToString("\n")
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "device_facts_get"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val key = keyOf(arguments)
                    if (key.isEmpty()) {
                        return@withContext ToolResult(false, "Укажи key. Доступные ключи: ${knownKeys()}")
                    }
                    if (!DeviceFactSpecs.isCacheable(key)) {
                        return@withContext ToolResult(
                            false,
                            "Неизвестный ключ «$key». Доступные ключи: ${knownKeys()}"
                        )
                    }
                    val at = now()
                    if (store.isFresh(key, at)) {
                        val fact = store.cached(key)
                            ?: return@withContext ToolResult(false, "Факт «$key» не сохранён.")
                        return@withContext ToolResult(true, describe(fact, fresh = true))
                    }
                    val refreshed = store.refresh(key)
                        ?: return@withContext ToolResult(
                            true,
                            "Факт «$key» устарел, но сейчас его не удалось собрать " +
                                "(нет разрешения или не поддерживается на этом устройстве)."
                        )
                    ToolResult(
                        true,
                        "Факт «$key» устарел и был собран заново " +
                            "(обновлено ${time(refreshed.updatedAt)}).\n${refreshed.valueJson}"
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "device_facts_refresh"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val key = keyOf(arguments)
                    if (key.isEmpty() || key.equals("all", ignoreCase = true)) {
                        val refreshed = store.refreshAll().sortedBy { it.key }
                        if (refreshed.isEmpty()) {
                            return@withContext ToolResult(
                                true,
                                "Не удалось собрать ни одного факта (нет доступа или не поддерживается)."
                            )
                        }
                        val names = refreshed.joinToString(", ") { it.key }
                        return@withContext ToolResult(
                            true,
                            "Обновлено фактов: ${refreshed.size}.\n$names"
                        )
                    }
                    if (!DeviceFactSpecs.isCacheable(key)) {
                        return@withContext ToolResult(
                            false,
                            "Неизвестный ключ «$key». Доступные ключи: ${knownKeys()}"
                        )
                    }
                    val refreshed = store.refresh(key)
                        ?: return@withContext ToolResult(
                            true,
                            "Факт «$key» сейчас не удалось собрать (нет разрешения или не поддерживается)."
                        )
                    ToolResult(
                        true,
                        "Факт «$key» обновлён (${time(refreshed.updatedAt)}).\n${refreshed.valueJson}"
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "device_facts_forget"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val key = keyOf(arguments)
                    if (key.isEmpty() || key.equals("all", ignoreCase = true)) {
                        val removed = store.forgetAll()
                        return@withContext ToolResult(
                            true,
                            "Кэш фактов об устройстве очищен (удалено: $removed)."
                        )
                    }
                    val removed = store.forget(key)
                    if (removed) {
                        ToolResult(true, "Факт «$key» удалён из кэша.")
                    } else {
                        ToolResult(true, "Факт «$key» и так не был сохранён.")
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
    )

    private fun describe(fact: DeviceFact, fresh: Boolean): String {
        val state = if (fresh) "свежий" else "устарел"
        val source = fact.sourceApi?.let { "$it" }.orEmpty()
        return buildString {
            append("Факт «${fact.key}» [$state], обновлён ${time(fact.updatedAt)}")
            if (source.isNotBlank()) append(", источник: $source")
            append(".\n").append(fact.valueJson)
        }
    }
}
