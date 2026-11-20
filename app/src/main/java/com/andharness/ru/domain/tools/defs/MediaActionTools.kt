package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- open / share a found media item (device_search results) ----
internal object MediaActionTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "media_open",
            description = "Открыть найденный файл или медиа в системном просмотрщике устройства " +
                "(ACTION_VIEW): фото — в галерее, документ — в программе просмотра. Используй для " +
                "результатов device_search — передай его uri (content://… или путь к файлу). Нужный " +
                "просмотрщик выбирается системой; mime определяется автоматически, если не задан. " +
                "Если открывать нечем — честно сообщит об этом.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("uri") {
                        put("type", "string")
                        put("description", "Ссылка content://… или путь к файлу, который нужно открыть (например, uri из device_search).")
                    }
                    putJsonObject("mime") {
                        put("type", "string")
                        put("description", "MIME-тип, например image/jpeg (опционально; по умолчанию определяется автоматически)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("uri")) }
            },
        ),
        Tool(
            name = "media_share",
            description = "Поделиться найденным файлом или медиа с другим приложением через системное " +
                "меню «Поделиться» (ACTION_SEND). Используй для результатов device_search — передай " +
                "его uri (content://… или путь к файлу); например, переслать найденное фото в " +
                "мессенджер. Доступ на чтение выдаётся получателю автоматически.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("uri") {
                        put("type", "string")
                        put("description", "Ссылка content://… или путь к файлу, которым нужно поделиться (например, uri из device_search).")
                    }
                    putJsonObject("mime") {
                        put("type", "string")
                        put("description", "MIME-тип вложения (опционально; по умолчанию определяется автоматически)")
                    }
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "Необязательная подпись к вложению")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("uri")) }
            },
        ),
    )
}
