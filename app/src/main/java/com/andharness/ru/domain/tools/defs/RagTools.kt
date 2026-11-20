package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- update.md idea #4: RAG over local files ----
internal object RagTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "rag_index",
            description = "Проиндексировать локальные файлы для поиска по содержимому: текст " +
                "документов (PDF, DOCX, XLSX, PPTX, ODT/ODS/ODP, RTF, CSV/TSV, TXT/MD/JSON/XML/HTML) " +
                "разбивается на фрагменты и сохраняется в локальный индекс на устройстве. " +
                "Повторный вызов пропускает файлы, которые не изменялись (укажи force=true, чтобы " +
                "переиндексировать принудительно). Папка обрабатывается на один уровень, если не " +
                "указан recursive=true. Возвращает честные счётчики: проиндексировано, пропущено, " +
                "ошибки, сохранено фрагментов. Текст никуда не отправляется и файлы не копируются.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь (в общем хранилище или /storage/emulated/0).")
                    }
                    putJsonObject("recursive") {
                        put("type", "boolean")
                        put("description", "Обходить папку рекурсивно (по умолчанию false — только один уровень).")
                    }
                    putJsonObject("force") {
                        put("type", "boolean")
                        put("description", "Переиндексировать даже неизменившиеся файлы (по умолчанию false).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "rag_query",
            description = "Найти ответ в уже проиндексированных локальных документах (rag_index). " +
                "Возвращает самые релевантные фрагменты с указанием источника (путь к файлу и номер " +
                "фрагмента) — цитируй их в ответе. Поиск идёт по смыслу запроса с учётом синонимов и " +
                "словоформ, работает офлайн. Если совпадений нет, честно сообщает об этом.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Вопрос или фраза для поиска по содержимому документов, например «что сказано про срок действия договора»")
                    }
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько фрагментов вернуть (по умолчанию 8, максимум 50).")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Необязательный фильтр: искать только в файлах, чей путь начинается с этой строки, например Documents/contracts")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("query")) }
            },
        ),
    )
}
