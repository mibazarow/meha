package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// ---- undo (M3 «Доверие»): reversible actions only ----
internal object UndoTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "undo_list",
            description = "Показать недавние действия, которые можно отменить, с человеческим " +
                "описанием (что именно было сделано). Только чтение. Честно сообщает, если " +
                "отменять нечего. Отменяемыми считаются только те действия, для которых " +
                "приложение точно знает обратную операцию (создание заметки, задачи, записи " +
                "памяти, расписания, контакта или события; запись системной настройки).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько последних действий показать (по умолчанию 20, максимум 50).")
                    }
                }
            },
        ),
        Tool(
            name = "undo_last",
            description = "Отменить самое последнее отменяемое действие: удалить только что " +
                "созданные заметку, задачу, запись памяти, расписание, контакт или событие в " +
                "календаре либо вернуть прежнее значение системной настройки. Работает только " +
                "если приложение знает обратную операцию; иначе честно сообщает, что отменить " +
                "нельзя. Если журнал отмены пуст, честно сообщает, что отменять нечего. " +
                "Необратимые действия (SMS, звонки, удаление приложений) отменить невозможно и в " +
                "журнал не попадают.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { }
            },
        ),
    )
}
