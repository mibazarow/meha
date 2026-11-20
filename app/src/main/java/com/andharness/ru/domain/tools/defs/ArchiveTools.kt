package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- archive ----
internal object ArchiveTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "files_tar_create",
            description = "Упаковать файлы/папки в TAR-архив (опционально с GZIP-сжатием).",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("sources") { put("type", "string"); put("description", "Пути через запятую.") }
                    putJsonObject("destination") { put("type", "string"); put("description", "Путь к .tar / .tar.gz.") }
                    putJsonObject("gzip") { put("type", "boolean"); put("description", "Сжать GZIP (true) или без сжатия (false).") }
                }
                putJsonArray("required") { add(JsonPrimitive("sources")); add(JsonPrimitive("destination")) }
            },
        ),
        Tool(
            name = "files_tar_extract",
            description = "Распаковать TAR / TAR.GZ архив.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("source") { put("type", "string"); put("description", "Путь к .tar / .tar.gz / .tgz архиву.") }
                    putJsonObject("destination") { put("type", "string"); put("description", "Папка для распаковки.") }
                }
                putJsonArray("required") { add(JsonPrimitive("source")); add(JsonPrimitive("destination")) }
            },
        ),
        Tool(
            name = "files_7z_extract",
            description = "Распаковать 7z-архив.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("source") { put("type", "string"); put("description", "Путь к .7z архиву.") }
                    putJsonObject("destination") { put("type", "string"); put("description", "Папка для распаковки.") }
                }
                putJsonArray("required") { add(JsonPrimitive("source")); add(JsonPrimitive("destination")) }
            },
        ),
        Tool(
            name = "files_7z_create",
            description = "Упаковать файлы/папки в 7z-архив.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("sources") { put("type", "string"); put("description", "Пути через запятую.") }
                    putJsonObject("destination") { put("type", "string"); put("description", "Путь к создаваемому .7z архиву.") }
                }
                putJsonArray("required") { add(JsonPrimitive("sources")); add(JsonPrimitive("destination")) }
            },
        ),
        Tool(
            name = "files_rar_extract",
            description = "Распаковать RAR-архив (только чтение).",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("source") { put("type", "string"); put("description", "Путь к .rar архиву.") }
                    putJsonObject("destination") { put("type", "string"); put("description", "Папка для распаковки. RAR только для чтения — создать RAR-архив невозможно.") }
                }
                putJsonArray("required") { add(JsonPrimitive("source")); add(JsonPrimitive("destination")) }
            },
        ),
    )
}
