package com.andharness.ru.data.tools.profile

import com.andharness.ru.data.profile.GlobalProfileStore
import com.andharness.ru.data.profile.ProfileRules
import com.andharness.ru.data.profile.ProfileWriteOutcome
import com.andharness.ru.data.tools.boolOrNull
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Tools over the always-on, cross-chat user profile ([GlobalProfileStore]):
 *
 * - `profile_list`   — read-only view of the explicitly saved facts (shared by every chat);
 * - `profile_set`    — save/update ONE durable fact (optional `id` to update, `text` required);
 * - `profile_forget` — delete one fact by `id`, or the whole profile with `all=true`.
 *
 * The profile is privacy-preserving by construction: only facts the user explicitly saved through
 * `profile_set` appear in it, and it is bounded both per fact and in total
 * ([ProfileRules.MAX_TEXT_CHARS]/[ProfileRules.MAX_ENTRIES]). None of these tools are dangerous —
 * they only touch Меха's own small profile store, never system data.
 */
class ProfileToolsHandler(private val store: GlobalProfileStore) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "profile_list"

            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                runCatching {
                    val facts = store.list()
                    if (facts.isEmpty()) {
                        ToolResult(true, "Профиль пуст — сохранённых фактов нет.")
                    } else {
                        ToolResult(
                            true,
                            buildString {
                                appendLine("Профиль пользователя (${facts.size}/${ProfileRules.MAX_ENTRIES}):")
                                facts.forEach { appendLine("- [${it.id}] ${it.text}") }
                            }.trim()
                        )
                    }
                }.getOrElse { ToolResult(false, "Ошибка чтения профиля: ${it.message}") }
            }
        },
        object : ToolHandler {
            override val name = "profile_set"

            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val text = arguments.str("text")
                        ?: return@withContext ToolResult(
                            false,
                            "Укажите text — один долговременный факт о пользователе."
                        )
                    val id = arguments.str("id")
                    val result = store.put(id, text)
                    when (result.outcome) {
                        ProfileWriteOutcome.CREATED ->
                            ToolResult(true, "Сохранено в профиль (#${result.fact?.id}).")
                        ProfileWriteOutcome.UPDATED ->
                            ToolResult(true, "Профиль обновлён (#${result.fact?.id}).")
                        ProfileWriteOutcome.DUPLICATE ->
                            ToolResult(
                                true,
                                "Такое уже сохранено (#${result.fact?.id}) — дубликат не создавал."
                            )
                        ProfileWriteOutcome.NOT_FOUND ->
                            ToolResult(
                                false,
                                "Запись #$id не найдена — посмотрите profile_list."
                            )
                        ProfileWriteOutcome.INVALID ->
                            ToolResult(
                                false,
                                "Факт пустой или длиннее ${ProfileRules.MAX_TEXT_CHARS} символов — " +
                                    "сформулируйте короче одной фразой."
                            )
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка сохранения в профиль: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "profile_forget"

            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val all = arguments.boolOrNull("all")
                    val id = arguments.str("id")
                    when {
                        all == true -> {
                            val cleared = store.clear()
                            ToolResult(
                                true,
                                if (cleared) "Профиль очищен." else "Профиль и так пуст."
                            )
                        }
                        id != null -> {
                            if (store.delete(id)) ToolResult(true, "Факт #$id удалён.")
                            else ToolResult(false, "Факт #$id не найден — посмотрите profile_list.")
                        }
                        else -> ToolResult(
                            false,
                            "Укажите id (из profile_list) или all=true, чтобы очистить профиль."
                        )
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка удаления из профиля: ${e.message}")
                }
            }
        },
    )
}
