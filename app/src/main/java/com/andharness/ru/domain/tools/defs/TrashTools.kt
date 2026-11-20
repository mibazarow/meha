package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- trash ----
internal object TrashTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "files_trash",
            description = "Переместить файл или папку в корзину приложения (можно восстановить). Без подтверждения — только по прямой просьбе пользователя. Путь в общем хранилище (/storage/emulated/0).",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь (в общем хранилище или /storage/emulated/0).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "files_trash_list",
            description = "Показать содержимое корзины приложения.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {}
            },
        ),
        Tool(
            name = "files_trash_restore",
            description = "Восстановить файл/папку из корзины приложения.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "Идентификатор записи из files_trash_list.")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("id")) }
            },
        ),
        Tool(
            name = "files_trash_empty",
            description = "Полностью очистить корзину приложения (без возможности восстановления). Необратимо, без подтверждения — только по прямой просьбе пользователя.",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {}
            },
        ),
    )
}
