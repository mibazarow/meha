package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- schedule (cron) ----
internal object ScheduleTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "schedule_create",
            description = "Создать задачу по расписанию (аналог cron): агент сам выполнит task в указанное время. time — «HH:MM» (24ч). days — «daily» (или каждый день), «weekdays» (будни), «weekends» (выходные) или список дней «mon,wed,fri»/«пн,ср,пт» (по умолчанию ежедневно). Например: каждое утро в 08:00 присылать курс USD и погоду.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("time") {
                        put("type", "string")
                        put("description", "Время запуска в формате HH:MM (24 часа), например 08:00")
                    }
                    putJsonObject("task") {
                        put("type", "string")
                        put("description", "Что должен сделать агент при срабатывании (текст задачи/промпт)")
                    }
                    putJsonObject("days") {
                        put("type", "string")
                        put("description", "Дни: daily | weekdays | weekends | список (mon,wed,fri / пн,ср,пт). По умолчанию daily")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("time"))
                    add(JsonPrimitive("task"))
                }
            },
        ),
        Tool(
            name = "schedule_list",
            description = "Показать список всех задач по расписанию (id, время, дни, статус, текст задачи).",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "schedule_delete",
            description = "Удалить задачу по расписанию. Укажи id (из schedule_list) или query — подстроку текста задачи; удаляются все подходящие.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "Задача (id из schedule_list)")
                    }
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Подстрока текста задачи для поиска (если id неизвестен)")
                    }
                }
            },
        ),
        Tool(
            name = "schedule_toggle",
            description = "Включить или выключить задачу по расписанию (не удаляя её). Укажи id или query, и enabled (true/false).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "Задача (id из schedule_list)")
                    }
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Подстрока текста задачи для поиска")
                    }
                    putJsonObject("enabled") {
                        put("type", "boolean")
                        put("description", "true — включить, false — выключить")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("enabled")) }
            },
        ),
        Tool(
            name = "reminder_set",
            description = "Поставить разовое напоминание («напомни через 5 минут …»). Для относительного времени укажи delay: 30s, 5m, 2h, 1d или просто число минут (5). Для абсолютного — at: epoch-время или HH:MM (ближайшее наступление). Ровно одно из delay/at. Срабатывает один раз; доставка может немного задержаться.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "Текст напоминания — что напомнить")
                    }
                    putJsonObject("delay") {
                        put("type", "string")
                        put("description", "Через сколько напомнить: 30s, 5m, 2h, 1d или число минут (5). Взаимоисключающе с at")
                    }
                    putJsonObject("at") {
                        put("type", "string")
                        put("description", "Когда напомнить: epoch (секунды или миллисекунды) либо HH:MM (24ч, ближайшее наступление). Взаимоисключающе с delay")
                    }
                    putJsonObject("chat_id") {
                        put("type", "string")
                        put("description", "Необязательно: id чата для доставки (по умолчанию текущий чат)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("text")) }
            },
        ),
    )
}
