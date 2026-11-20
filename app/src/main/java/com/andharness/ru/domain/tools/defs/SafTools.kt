package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- saf ----
internal object SafTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "saf_pick_tree",
            description = "Попросить пользователя выбрать папку (SAF) и сохранить постоянный доступ к ней.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("title") {
                        put("type", "string")
                        put("description", "Заголовок диалога выбора папки (необязательно).")
                    }
                }
            },
        ),
        Tool(
            name = "saf_grants_list",
            description = "Показать сохранённые папки (SAF-гранты) и проверить, доступны ли они сейчас.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {}
            },
        ),
        Tool(
            name = "saf_grant_remove",
            description = "Отозвать сохранённый доступ к папке (SAF-грант).",
            dangerous = true,
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("grant_id") {
                        put("type", "string")
                        put("description", "Грант (id из saf_grants_list).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("grant_id")) }
            },
        ),
        Tool(
            name = "saf_grant_test",
            description = "Проверить, доступен ли сохранённый SAF-грант прямо сейчас.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("grant_id") {
                        put("type", "string")
                        put("description", "Грант (id из saf_grants_list).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("grant_id")) }
            },
        ),
        Tool(
            name = "saf_list",
            description = "Показать содержимое папки внутри сохранённого SAF-гранта. path — путь относительно корня выбранной папки (пусто = корень).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("grant_id") {
                        put("type", "string")
                        put("description", "Грант (id из saf_grants_list).")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к папке относительно корня гранта (необязательно; пусто — корень).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("grant_id")) }
            },
        ),
        Tool(
            name = "saf_read",
            description = "Прочитать текстовый файл внутри сохранённого SAF-гранта. path — путь к файлу относительно корня выбранной папки.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("grant_id") {
                        put("type", "string")
                        put("description", "Грант (id из saf_grants_list).")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к файлу внутри гранта.")
                    }
                    putJsonObject("offset") {
                        put("type", "string")
                        put("description", "Смещение в байтах, с которого начать чтение (необязательно).")
                    }
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько байт прочитать (необязательно; по умолчанию 1 МБ, максимум 64 МБ).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("grant_id"))
                    add(JsonPrimitive("path"))
                }
            },
        ),
        Tool(
            name = "saf_write",
            description = "Записать текстовое содержимое в файл внутри сохранённого SAF-гранта; создаёт промежуточные папки и файл при необходимости.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("grant_id") {
                        put("type", "string")
                        put("description", "Грант (id из saf_grants_list).")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к файлу внутри гранта.")
                    }
                    putJsonObject("content") {
                        put("type", "string")
                        put("description", "Текстовое содержимое для записи.")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "Перезаписать файл (по умолчанию true).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("grant_id"))
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("content"))
                }
            },
        ),
        Tool(
            name = "saf_mkdir",
            description = "Создать папку (включая промежуточные) внутри сохранённого SAF-гранта.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("grant_id") {
                        put("type", "string")
                        put("description", "Грант (id из saf_grants_list).")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к создаваемой папке относительно корня гранта.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("grant_id"))
                    add(JsonPrimitive("path"))
                }
            },
        ),
        Tool(
            name = "saf_delete",
            description = "Удалить файл или папку внутри сохранённого SAF-гранта. Необратимо, без подтверждения — только по прямой просьбе пользователя.",
            dangerous = true,
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("grant_id") {
                        put("type", "string")
                        put("description", "Грант (id из saf_grants_list).")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к файлу или папке относительно корня гранта.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("grant_id"))
                    add(JsonPrimitive("path"))
                }
            },
        ),
        Tool(
            name = "saf_create_document",
            description = "Создать документ через системный диалог «Сохранить как» (SAF).",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("filename") {
                        put("type", "string")
                        put("description", "Имя файла для сохранения.")
                    }
                    putJsonObject("mime") {
                        put("type", "string")
                        put("description", "MIME-тип (необязательно; по умолчанию application/octet-stream).")
                    }
                    putJsonObject("content") {
                        put("type", "string")
                        put("description", "Содержимое файла (необязательно; если указано — будет записано сразу).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("filename")) }
            },
        ),
    )
}
