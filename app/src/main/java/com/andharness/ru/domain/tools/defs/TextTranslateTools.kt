package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- text / translation ----
internal object TextTranslateTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "translate_text",
            description = "Перевести текст на указанный язык с помощью настроенной языковой модели " +
                "(внешний сервис перевода не нужен). Передай переводимый текст в text, целевой язык " +
                "в to (код ru/en/de или название «немецкий»), при желании исходный язык во from " +
                "и стиль в tone (нейтральный/формальный/разговорный). Инструмент возвращает только " +
                "сам перевод, без пояснений, и честно сообщает об ошибке, если модель не настроена " +
                "или не ответила.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "Текст для перевода")
                    }
                    putJsonObject("to") {
                        put("type", "string")
                        put("description", "Целевой язык: код (ru, en, de) или название")
                    }
                    putJsonObject("from") {
                        put("type", "string")
                        put("description", "Исходный язык (по умолчанию — авто)")
                    }
                    putJsonObject("tone") {
                        put("type", "string")
                        put("description", "Желаемый стиль перевода (необязательно): нейтральный, формальный, разговорный и т.п.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("text"))
                    add(JsonPrimitive("to"))
                }
            },
        ),
        Tool(
            name = "translate_chat",
            description = "Перевести последние сообщения переписки на указанный язык с помощью " +
                "настроенной языковой модели (внешний сервис перевода не нужен). Передай целевой " +
                "язык в to (код ru/en/de или название «немецкий»), при желании исходный язык во " +
                "from, идентификатор чата в chat_id (по умолчанию — самый свежий чат) и число " +
                "последних сообщений в limit (по умолчанию 20, максимум 50). Сообщения переводятся " +
                "одним запросом и возвращаются построчным списком (автор + перевод) в исходном " +
                "порядке; содержимое чата не меняется. Честно сообщает, если чат не найден, пуст " +
                "или модель не настроена.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("to") {
                        put("type", "string")
                        put("description", "Целевой язык: код (ru, en, de) или название")
                    }
                    putJsonObject("chat_id") {
                        put("type", "string")
                        put("description", "Чат (id; по умолчанию — самый свежий).")
                    }
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько последних сообщений перевести, 1–50 (по умолчанию 20)")
                    }
                    putJsonObject("from") {
                        put("type", "string")
                        put("description", "Исходный язык (по умолчанию — авто)")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("to"))
                }
            },
        ),
        Tool(
            name = "translate_screen",
            description = "Перевести текст, который сейчас виден на экране: снять экран через службу " +
                "спецвозможностей, распознать текст офлайн-OCR и перевести его на указанный язык " +
                "настроенной языковой моделью. Передай целевой язык в to (код ru/en/de или название " +
                "«немецкий»), при желании исходный язык во from; include_original=true дополнительно " +
                "вернёт распознанный оригинал. Требует включённой службы спецвозможностей Меха; честно " +
                "сообщает, если экран снять не удалось (например защищённое окно) или текст не " +
                "распознан.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("to") {
                        put("type", "string")
                        put("description", "Целевой язык: код (ru, en, de) или название")
                    }
                    putJsonObject("from") {
                        put("type", "string")
                        put("description", "Исходный язык (по умолчанию — авто)")
                    }
                    putJsonObject("include_original") {
                        put("type", "boolean")
                        put("description", "Вернуть также распознанный текст экрана (по умолчанию false)")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("to"))
                }
            },
        ),
    )
}
