package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// ---- clipboard history ----
internal object ClipboardTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "clipboard_history_list",
            description = "Показать недавнюю историю буфера обмена (номер, время, источник и " +
                "короткий фрагмент). Только чтение. Номер записи передавай в clipboard_history_get. " +
                "Честно про ограничение платформы: Android 10+ не даёт читать буфер обмена в фоне, " +
                "поэтому в истории есть только то, что скопировала сама Меха (`clipboard_set`), и " +
                "то, что лежало в буфере в момент открытия приложения (когда у него есть фокус). " +
                "Текст, скопированный в другом приложении, появится здесь не сразу, а при " +
                "возвращении в Меху.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько последних записей показать (по умолчанию 20, максимум 50).")
                    }
                }
            },
        ),
        Tool(
            name = "clipboard_history_get",
            description = "Достать ПОЛНЫЙ текст из истории буфера обмена, чтобы затем вставить его " +
                "(например, через clipboard_set или ui_type). Укажи index (номер из " +
                "clipboard_history_list, 1 — самая свежая) или id; без параметров возвращает самый " +
                "свежий фрагмент — это «умная вставка». Честно сообщает, если истории нет или " +
                "запись не найдена; системный буфер обмена при этом не меняется.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("index") {
                        put("type", "string")
                        put("description", "Номер записи из clipboard_history_list (1 — самая свежая)")
                    }
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "Идентификатор записи (альтернатива index)")
                    }
                }
            },
        ),
        Tool(
            name = "clipboard_history_clear",
            description = "Очистить историю буфера обмена (все сохранённые записи). Действие не " +
                "опасное и выполняется сразу, без подтверждения. Системный буфер обмена при этом " +
                "не очищается — для этого используй clipboard_set. Честно сообщает, сколько " +
                "записей удалено.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
    )
}
