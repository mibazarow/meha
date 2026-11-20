package com.andharness.ru.data.tools.alarm

import com.andharness.ru.data.alarm.ExactAlarmScheduler
import com.andharness.ru.domain.alarm.ExactAlarm
import com.andharness.ru.domain.alarm.ExactAlarmRepository
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.chat.CurrentChatProvider
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.Calendar
import com.andharness.ru.data.tools.str
import com.andharness.ru.data.tools.formatTimestamp

/**
 * Builds one [ToolHandler] per exact-alarm tool: [alarm_exact_set], [alarm_exact_list],
 * [alarm_exact_cancel]. Alarms are persisted in Room (`exact_alarms`, DB v12) so they survive
 * process restarts and are re-armed after reboot by [com.andharness.ru.data.alarm.BootCompletedReceiver].
 *
 * Official references:
 * - `AlarmManager.canScheduleExactAlarms()` + the Android 14 change (SCHEDULE_EXACT_ALARM is no
 *   longer pre-granted to apps targeting API 33+):
 *   https://developer.android.com/about/versions/14/changes/schedule-exact-alarms
 * - `AlarmManager.setAlarmClock` / `setAndAllowWhileIdle` / `PendingIntent` request-code uniqueness:
 *   https://developer.android.com/reference/android/app/AlarmManager
 * - `PendingIntent` mutability and cancellation:
 *   https://developer.android.com/reference/android/app/PendingIntent
 */
class AlarmToolsHandler(
    private val repository: ExactAlarmRepository,
    private val scheduler: ExactAlarmScheduler,
    private val currentChat: CurrentChatProvider = CurrentChatProvider(),
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "alarm_exact_set"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val now = System.currentTimeMillis()
                    val rawTime = arguments.str("time")
                        ?: return@withContext ToolResult(
                            false,
                            "Укажите time — время срабатывания: epoch-миллисекунды или HH:MM (ближайшее наступление).",
                        )
                    val atMs = parseTimeMs(rawTime, now)
                        ?: return@withContext ToolResult(
                            false,
                            "Не удалось разобрать time «$rawTime». Формат: epoch-миллисекунды (например 1735689600000) " +
                                "или время HH:MM (например 07:30).",
                        )
                    if (atMs <= now) {
                        return@withContext ToolResult(
                            false,
                            "Указанное время уже прошло (${format(atMs)}); будильник не создан. " +
                                "Укажите будущее время: epoch-миллисекунды больше текущих или HH:MM.",
                        )
                    }
                    val label = arguments.str("label").orEmpty()
                    val chatId = arguments.str("chat_id")?.toLongOrNull() ?: currentChat.chatId()

                    val id = repository.create(
                        ExactAlarm(atMs = atMs, label = label, requestCode = 0, chatId = chatId)
                    )
                    // Stable, unique PendingIntent request code: the row id.
                    val requestCode = id.toInt()
                    repository.setRequestCode(id, requestCode)
                    val alarm = repository.getById(id)
                        ?: ExactAlarm(id = id, atMs = atMs, label = label, requestCode = requestCode, chatId = chatId)

                    val exact = scheduler.schedule(alarm)
                    val exactNote = if (exact) {
                        "Будильник точный."
                    } else {
                        "Внимание: разрешение SCHEDULE_EXACT_ALARM не выдано, поэтому использован неточный режим " +
                            "срабатывания (система может отложить его, особенно в режиме энергосбережения). " +
                            "Точные будильники пользователь может разрешить в настройках «Будильники и напоминания»."
                    }
                    ToolResult(true, "Будильник #$id создан на ${format(atMs)}. $exactNote")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка создания будильника: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "alarm_exact_list"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val alarms = repository.getAll()
                    if (alarms.isEmpty()) {
                        ToolResult(true, "Сохранённых будильников нет.")
                    } else {
                        ToolResult(
                            true,
                            "Будильники (${alarms.size}):\n" + alarms.joinToString("\n") { a ->
                                val state = if (a.fired) "сработал" else "активен"
                                val label = a.label.ifBlank { "без подписи" }
                                "#${a.id} | ${format(a.atMs)} | $state | $label"
                            },
                        )
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка чтения будильников: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "alarm_exact_cancel"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val id = arguments.str("id")?.toLongOrNull()
                        ?: return@withContext ToolResult(
                            false,
                            "Укажите id будильника (список — в alarm_exact_list).",
                        )
                    val alarm = repository.getById(id)
                        ?: return@withContext ToolResult(false, "Будильник #$id не найден.")
                    scheduler.cancel(alarm)
                    val deleted = repository.delete(id)
                    if (!deleted || repository.getById(id) != null) {
                        ToolResult(false, "Будильник #$id отменён, но запись не удалось удалить из базы.")
                    } else {
                        ToolResult(true, "Будильник #$id отменён и удалён (срабатывание на ${format(alarm.atMs)} отменено).")
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка отмены будильника: ${e.message}")
                }
            }
        },
    )

    /**
     * Accepts epoch millis (a run of >= 9 digits) or `HH:MM` (also `HH.MM` / `HH-MM`), which is
     * resolved to the NEXT occurrence today or tomorrow. Returns null for anything invalid.
     */
    private fun parseTimeMs(raw: String, now: Long): Long? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (t.all { it.isDigit() } && t.length >= 9) {
            return t.toLongOrNull()
        }
        val m = Regex("^(\\d{1,2})[:.\\-](\\d{2})$").find(t) ?: return null
        val hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun format(ms: Long): String = formatTimestamp(ms)

}
