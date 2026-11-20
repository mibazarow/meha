package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- BUG-018: schedule_message_list / schedule_message_cancel ----
internal object ScheduleMessageListTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "schedule_message_list",
            description = "Показать ещё не сработавшие отложенные сообщения (созданные " +
                "schedule_message). Для каждого: id, получатель, канал, время отправки, короткий " +
                "фрагмент текста и статус (ожидает / просрочено). Только чтение; ничего не отправляет. " +
                "Честно: отправленные сообщения в хранилище не остаются, поэтому в списке их нет — об " +
                "отправке сообщает отдельное уведомление. Чтобы отменить сообщение, используй " +
                "schedule_message_cancel с его id.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {}
            },
        ),
        Tool(
            name = "schedule_message_cancel",
            description = "Отменить отложенное сообщение по id (id выдаёт schedule_message и " +
                "показывает schedule_message_list). Отмена снимает задачу с планировщика WorkManager " +
                "(cancelUniqueWork) и удаляет её из локального хранилища, поэтому сообщение НЕ будет " +
                "отправлено. Если id уже не найден (сообщение отправлено или отменено ранее), " +
                "инструмент честно сообщает об этом, а не считает это ошибкой. Отмена — best-effort: " +
                "если задача уже выполняется, она может успеть завершиться. Опасное действие (отмена " +
                "запланированной отправки).",
            dangerous = true,
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "id отложенного сообщения (см. schedule_message_list).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("id"))
                }
            },
        ),
    )
}
