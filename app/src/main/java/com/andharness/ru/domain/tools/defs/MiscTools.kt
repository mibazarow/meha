package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- misc ----
internal object MiscTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "clipboard_get",
            description = "Получить текущее содержимое буфера обмена (текст). Возвращает пустую строку, если буфер пуст.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "clipboard_set",
            description = "Записать текст в буфер обмена. Чтобы вставить текст в другое приложение, скопируй его из буфера через clipboard_get.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "Текст для записи в буфер обмена")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("text")) }
            },
        ),
    )
}
