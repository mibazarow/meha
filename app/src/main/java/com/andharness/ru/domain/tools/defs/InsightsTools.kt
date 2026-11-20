package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- proactive insights (checklist §1) ----
internal object InsightsTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "insights_list",
            description = "Проактивные инсайты о ваших привычках, собранные ТОЛЬКО из локальных данных " +
                "устройства: повторяющиеся действия по дням недели (журнал вызовов инструментов), давно " +
                "не использованные навыки и приложения с наибольшим числом уведомлений. Меха ничего не " +
                "выдумывает: инсайт появляется только при достаточном числе наблюдений, а если данных мало — " +
                "честно сообщает, что инсайтов пока нет. kind — необязательный фильтр: routine, " +
                "stale_skill, notification_load; limit — сколько показать (по умолчанию 20, максимум 100).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("kind") {
                        put("type", "string")
                        put("description", "Фильтр раздела: routine (повторяющиеся действия), stale_skill (неиспользуемые навыки), notification_load (нагрузка уведомлений); необязательно")
                    }
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько инсайтов показать, 1–100 (по умолчанию 20)")
                    }
                }
                putJsonArray("required") { }
            },
        ),
    )
}
