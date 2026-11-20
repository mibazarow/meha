package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- update.md §4.8: schedule_message ----
internal object ScheduleMessageTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "schedule_message",
            description = "Отложенная отправка сообщения человеку: это ПРЯМАЯ отправка сообщения (не " +
                "запуск LLM-задачи), в отличие от schedule_create. to — имя/номер/ник, text — текст, " +
                "at — время: epoch millis или HH:MM (следующее наступление этого времени), " +
                "необязательные channel (AUTO/SMS/MESSENGER) и chat_id (по умолчанию текущий чат). " +
                "Персона и канал резолвятся СЕЙЧАС (при неоднозначности — вопрос, без планирования) и " +
                "сохраняются в локальном хранилище отложенных задач (без таблиц Room); затем ставится " +
                "одноразовый WorkManager. В момент срабатывания сообщение отправляется тем же путём, " +
                "что и compose_message, а отчёт о доставке постится уведомлением с переходом в " +
                "исходный чат. Честно: опоздание, недоступная цель/канал или неподтверждённая " +
                "отправка отражаются в уведомлении; тела сообщений в персональной базе не хранятся. " +
                "Опасное действие (отложенная отправка).",
            dangerous = true,
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("to") {
                        put("type", "string")
                        put(
                            "description",
                            "Получатель: имя/алиас человека, номер телефона или ник (обязательно)."
                        )
                    }
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "Текст сообщения (обязательно).")
                    }
                    putJsonObject("at") {
                        put("type", "string")
                        put(
                            "description",
                            "Время отправки: epoch millis (например 1730000000000) или HH:MM " +
                                "(например 09:30 — ближайшее наступление). Обязательно."
                        )
                    }
                    putJsonObject("channel") {
                        put("type", "string")
                        put("description", "Канал: AUTO (по умолчанию), SMS или MESSENGER.")
                    }
                    putJsonObject("chat_id") {
                        put("type", "string")
                        put(
                            "description",
                            "Необязательный id чата для отчёта о доставке; по умолчанию текущий чат."
                        )
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("to"))
                    add(JsonPrimitive("text"))
                    add(JsonPrimitive("at"))
                }
            },
        ),
    )
}
