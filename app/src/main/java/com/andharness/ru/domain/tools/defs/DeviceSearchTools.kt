package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- whole-device semantic search (device_index_vectors) ----
internal object DeviceSearchTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "device_search",
            description = "Семантический поиск ПО СМЫСЛУ по всему устройству: фотографии в галерее и " +
                "текст документов (PDF/DOCX/TXT), которые уже проиндексированы на устройстве. " +
                "Запрос можно задать словами, не точным именем файла: «фото с замком», «договор со " +
                "сроком действия». Работает офлайн на встроенной модели эмбеддингов. Возвращает " +
                "найденные файлы (для фото — uri, путь и имя; для документов — имя и фрагмент текста). " +
                "Найденное можно открыть тулом media_open(uri=…) или отправить media_share(uri=…). " +
                "Честно сообщает, если индекс ещё пуст или модель эмбеддингов не скачана.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Что искать, словами: смысл, объект или тему, например «фото с замком» или «договор аренды».")
                    }
                    putJsonObject("type") {
                        put("type", "string")
                        put("description", "Ограничить тип: any (по умолчанию), image (только фото) или document (только документы).")
                        putJsonArray("enum") {
                            add(JsonPrimitive("any"))
                            add(JsonPrimitive("image"))
                            add(JsonPrimitive("document"))
                        }
                    }
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько результатов вернуть (по умолчанию 10, максимум 30).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("query")) }
            },
        ),
    )
}
