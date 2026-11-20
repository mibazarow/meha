package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- watch ----
internal object WatchTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "folder_watch_start",
            description = "Начать наблюдение за папкой (события create/modify/delete/move). Наблюдение живёт только до перезапуска приложения и не является рекурсивным: следят только за указанной папкой, не за подпапками.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Абсолютный путь к папке (в пределах хранилища).")
                    }
                    putJsonObject("mask") {
                        put("type", "string")
                        put("description", "create | modify | delete | move | all (по умолчанию all).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "folder_watch_stop",
            description = "Остановить наблюдение за папкой по его watch_id.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("watch_id") {
                        put("type", "string")
                        put("description", "Наблюдение (id из folder_watch_list).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("watch_id")) }
            },
        ),
        Tool(
            name = "folder_watch_list",
            description = "Показать активные наблюдения за папками. После перезапуска приложения наблюдений нет.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {}
            },
        ),
        Tool(
            name = "folder_watch_events",
            description = "Показать последние накопленные события наблюдения (буфер не очищается).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("watch_id") {
                        put("type", "string")
                        put("description", "Наблюдение (id из folder_watch_list).")
                    }
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько последних событий вернуть (по умолчанию 100).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("watch_id")) }
            },
        ),
        Tool(
            name = "folder_watch_clear",
            description = "Очистить буфер накопленных событий наблюдения.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("watch_id") {
                        put("type", "string")
                        put("description", "Наблюдение (id из folder_watch_list).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("watch_id")) }
            },
        ),
        Tool(
            name = "saf_open_document",
            description = "Выбрать документ через системный диалог и вернуть его метаданные (и текст, если это небольшой текстовый файл).",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("mime") {
                        put("type", "string")
                        put("description", "MIME-тип для фильтра (необязательно; по умолчанию */*).")
                    }
                }
            },
        ),
    )
}
