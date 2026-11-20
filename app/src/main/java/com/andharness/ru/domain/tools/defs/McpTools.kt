package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- MCP (Model Context Protocol) server management, entirely from chat ----
internal object McpTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "mcp_server_add",
            description = "Добавить или обновить внешний MCP-сервер (Model Context Protocol) прямо из " +
                "чата — отдельный экран настроек не нужен. Укажи понятное имя и URL сервера; транспорт " +
                "обычно определяется автоматически (Streamable HTTP, иначе устаревший HTTP+SSE). Если " +
                "сервер требует токен, задай имя заголовка (например, Authorization или X-Api-Key) и его " +
                "значение: значение хранится зашифрованно и никогда не показывается в ответах. Повторный " +
                "вызов с тем же именем обновляет сервер и снова включает его. После добавления проверь " +
                "связь через mcp_server_test.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Короткое имя сервера для управления им из чата (например, «Погода»).")
                    }
                    putJsonObject("url") {
                        put("type", "string")
                        put("description", "Полный URL MCP-сервера (http:// или https://). Без схемы подставляется https://.")
                    }
                    putJsonObject("transport") {
                        put("type", "string")
                        put("description", "Транспорт: auto (по умолчанию), streamable_http или sse.")
                        putJsonArray("enum") {
                            add(JsonPrimitive("auto"))
                            add(JsonPrimitive("streamable_http"))
                            add(JsonPrimitive("sse"))
                        }
                    }
                    putJsonObject("auth_header_name") {
                        put("type", "string")
                        put("description", "Имя заголовка авторизации, если сервер требует токен (например, Authorization или X-Api-Key). Для Authorization значение без пробела автоматически получит префикс Bearer.")
                    }
                    putJsonObject("auth_header_value") {
                        put("type", "string")
                        put("description", "Значение токена/ключа. Хранится зашифрованно (Android Keystore) и никогда не возвращается в ответах и не попадает в файл конфигурации.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("name"))
                    add(JsonPrimitive("url"))
                }
            },
        ),
        Tool(
            name = "mcp_server_list",
            description = "Показать настроенные MCP-серверы: имя, URL, транспорт, включён ли сервер, " +
                "задан ли токен, и подсказку по статусу. Честно сообщает, если серверов ещё нет.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { }
            },
        ),
        Tool(
            name = "mcp_server_remove",
            description = "Удалить MCP-сервер по имени или id вместе с сохранённым токеном. Честно " +
                "сообщает, если такого сервера нет.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Имя (или id) сервера, который нужно удалить.")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("name")) }
            },
        ),
        Tool(
            name = "mcp_server_test",
            description = "Проверить MCP-сервер: подключиться, выполнить рукопожатие initialize и получить " +
                "список инструментов (tools/list). Возвращает имя и версию сервера, реально " +
                "использованный транспорт и найденные инструменты. При ошибке честно сообщает причину " +
                "(нет сети, неверный URL, HTTP-статус, таймаут).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Имя (или id) сервера для проверки.")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("name")) }
            },
        ),
    )
}
