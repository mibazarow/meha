package com.andharness.ru.data.tools.schedule

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.chat.CurrentChatProvider
import com.andharness.ru.domain.common.TimeProvider
import com.andharness.ru.data.schedule.ScheduledTaskActions
import com.andharness.ru.domain.schedule.ReminderTime
import com.andharness.ru.domain.schedule.ScheduleRepository
import com.andharness.ru.domain.schedule.ScheduledTask
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.undo.UndoKinds
import com.andharness.ru.domain.undo.UndoPayload
import com.andharness.ru.domain.undo.UndoRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.andharness.ru.data.tools.str
import java.time.Instant

/**
 * Builds one [ToolHandler] per cron tool: [reminder_set], [schedule_create], [schedule_list],
 * [schedule_delete], [schedule_toggle]. Full management is chat-driven: the agent creates, lists,
 * disables and deletes its own scheduled tasks.
 */
class ScheduleToolsHandler(
    private val repository: ScheduleRepository,
    private val actions: ScheduledTaskActions,
    private val currentChat: CurrentChatProvider = CurrentChatProvider(),
    private val timeProvider: TimeProvider,
    private val undoRecorder: UndoRecorder = UndoRecorder.NoOp,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "schedule_create"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val time = arguments.str("time")
                        ?: return@withContext ToolResult(false, "Укажите time в формате HH:MM.")
                    val parsed = parseTime(time)
                        ?: return@withContext ToolResult(false, "Не удалось разобрать время «$time». Формат: HH:MM.")
                    val prompt = arguments.str("task")
                        ?: return@withContext ToolResult(false, "Укажите task — что делать при срабатывании.")
                    val daysMask = ScheduledTask.parseDays(arguments.str("days"))
                        ?: return@withContext ToolResult(false, "Не удалось разобрать days: ${arguments.str("days")}.")
                    val id = repository.create(
                        ScheduledTask(
                            id = 0,
                            prompt = prompt,
                            hour = parsed.first,
                            minute = parsed.second,
                            daysMask = daysMask,
                            enabled = true,
                            lastRunAt = 0,
                            // §20: remember the chat this schedule was created from so its
                            // completion notification can deep-link back to it.
                            chatId = currentChat.chatId()
                        )
                    )
                    val task = repository.getById(id)
                    if (task != null) actions.schedule(task)
                    undoRecorder.record(
                        toolName = "schedule_create",
                        description = "Создано расписание #$id на ${"%02d:%02d".format(parsed.first, parsed.second)}: $prompt",
                        undoKind = UndoKinds.SCHEDULE_DELETE,
                        payload = mapOf(UndoPayload.ID to id.toString()),
                    )
                    ToolResult(true, "Создана задача #$id: ${"%02d:%02d".format(parsed.first, parsed.second)}, ${ScheduledTask.daysMaskToText(daysMask)} — $prompt")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "schedule_list"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val tasks = repository.getAll()
                    if (tasks.isEmpty()) {
                        ToolResult(true, "Задач по расписанию нет.")
                    } else {
                        ToolResult(true, tasks.joinToString("\n") { t ->
                            "#${t.id} | ${t.timeText} | ${t.daysText} | ${if (t.enabled) "вкл" else "выкл"} | ${t.prompt}"
                        })
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "schedule_delete"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val targets = resolveTargets(arguments, repository)
                    when {
                        targets == null -> return@withContext ToolResult(false, "Укажите id или query (текст задачи).")
                        targets.isEmpty() -> return@withContext ToolResult(false, "Подходящие задачи не найдены.")
                    }
                    val removed = ArrayList<String>()
                    val survivors = ArrayList<String>()
                    for (t in targets) {
                        val ok = actions.delete(t.id)
                        // Post-check (BUG-059): a delete counts only when the row is really gone.
                        val gone = repository.getById(t.id) == null
                        if (ok && gone) {
                            removed.add("#${t.id} (${t.timeText}, ${t.prompt.take(40)})")
                        } else {
                            survivors.add("#${t.id}")
                        }
                    }
                    when {
                        survivors.isNotEmpty() -> ToolResult(
                            false,
                            "Удалено задач: ${removed.size}, но ${survivors.size} остались " +
                                "(${survivors.joinToString(", ")}) — НЕ подтверждено."
                        )
                        removed.isEmpty() -> ToolResult(
                            false,
                            "Не удалось удалить ни одной задачи из ${targets.size} найденных — " +
                                "действие НЕ выполнено."
                        )
                        else -> ToolResult(true, "Удалено задач: ${removed.size}\n" + removed.joinToString("\n"))
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "schedule_toggle"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val enabled = (arguments["enabled"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                        ?: return@withContext ToolResult(false, "Укажите enabled (true/false).")
                    val targets = resolveTargets(arguments, repository)
                    when {
                        targets == null -> return@withContext ToolResult(false, "Укажите id или query (текст задачи).")
                        targets.isEmpty() -> return@withContext ToolResult(false, "Подходящие задачи не найдены.")
                    }
                    val changed = ArrayList<String>()
                    for (t in targets) {
                        if (actions.setEnabled(t.id, enabled)) {
                            changed.add("#${t.id} (${t.timeText}, ${t.prompt.take(40)})")
                        }
                    }
                    ToolResult(true, "Задачи ${if (enabled) "включены" else "выключены"}: ${changed.size}\n" + changed.joinToString("\n"))
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "reminder_set"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val text = arguments.str("text")
                        ?: return@withContext ToolResult(false, "Укажи text — что напомнить.")
                    val atValue = arguments.str("at")
                    val delayValue = arguments.str("delay")
                    if (atValue != null && delayValue != null) {
                        return@withContext ToolResult(false, "Укажи только одно из полей at или delay, не оба.")
                    }
                    if (atValue == null && delayValue == null) {
                        return@withContext ToolResult(false, "Укажи ровно одно из полей: at (когда) или delay (через сколько).")
                    }
                    val now = timeProvider.nowMillis()
                    val zone = timeProvider.zoneId()
                    // ReminderTime is the single pure parser for both forms; its error text is
                    // already user-facing Russian, so it is returned verbatim (SPEC §7, §23).
                    val resolved = if (delayValue != null) {
                        ReminderTime.fromDelay(delayValue, now)
                    } else {
                        ReminderTime.fromAt(atValue, now, zone)
                    }
                    val atMs = when (resolved) {
                        is ReminderTime.Result.Ok -> resolved.atMs
                        is ReminderTime.Result.Error -> return@withContext ToolResult(false, resolved.message)
                    }
                    val local = Instant.ofEpochMilli(atMs).atZone(zone)
                    // §20: remember the chat so the completion notification can deep-link back to it.
                    val chatId = arguments.str("chat_id")?.toLongOrNull() ?: currentChat.chatId()
                    val id = repository.create(
                        ScheduledTask(
                            id = 0,
                            prompt = text,
                            // The DB columns are NOT NULL; for a one-shot task these are merely
                            // derived from atMs and never used for scheduling (see ScheduledTask).
                            hour = local.hour,
                            minute = local.minute,
                            daysMask = ScheduledTask.EVERY_DAY,
                            enabled = true,
                            lastRunAt = 0,
                            chatId = chatId,
                            atMs = atMs
                        )
                    )
                    val task = repository.getById(id)
                    if (task != null) actions.schedule(task)
                    val whenText = "%02d.%02d.%04d %02d:%02d".format(
                        local.dayOfMonth, local.monthValue, local.year, local.hour, local.minute
                    )
                    ToolResult(
                        true,
                        "Напоминание #$id на $whenText (${zone.id}): $text. " +
                            "Доставка может немного задержаться — системный планировщик неточный."
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
    )

    /** Resolves the target tasks by explicit `id`, else by `query` substring match. */
    private suspend fun resolveTargets(arguments: JsonObject, repository: ScheduleRepository): List<ScheduledTask>? {
        val id = arguments.str("id")?.toLongOrNull()
        if (id != null) {
            val task = repository.getById(id) ?: return emptyList()
            return listOf(task)
        }
        val query = arguments.str("query")
        if (query != null) {
            return repository.getAll().filter { it.prompt.contains(query, ignoreCase = true) }
        }
        return null
    }

    private fun parseTime(raw: String): Pair<Int, Int>? {
        val m = Regex("^\\s*(\\d{1,2})[:.\\-](\\d{2})\\s*$").find(raw.trim()) ?: return null
        val hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

}
