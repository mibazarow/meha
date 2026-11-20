package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- sms ----
internal object SmsTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "sms_list",
            description = "Список входящих СМС (по желанию — поиск по тексту тела). Возвращает отправителя, тело и дату.",
            needsPermission = "android.permission.READ_SMS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Текст для поиска по тела СМС (опционально)")
                    }
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Максимальное количество возвращаемых сообщений")
                    }
                }
            },
        ),
        Tool(
            name = "sms_send",
            description = "Отправить СМС на указанный номер с текстом. Требует разрешения SEND_SMS.",
            dangerous = true,
            needsPermission = "android.permission.SEND_SMS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("phone") {
                        put("type", "string")
                        put("description", "Номер телефона получателя")
                    }
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "Текст сообщения")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("phone"))
                    add(JsonPrimitive("text"))
                }
            },
        ),
        Tool(
            name = "sms_conversations",
            description = "Список диалогов (нитей) SMS: id нити, число сообщений и фрагмент последнего. Используй thread_id для sms_read_state.",
            needsPermission = "android.permission.READ_SMS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Максимальное количество диалогов (по умолчанию 50)")
                    }
                }
            },
        ),
        Tool(
            name = "sms_read_state",
            description = "Пометить сообщения диалога прочитанными или непрочитанными. Работает только если приложение — приложение SMS по умолчанию; иначе честно сообщает об ограничении платформы.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("thread_id") {
                        put("type", "string")
                        put("description", "Идентификатор диалога (из sms_conversations)")
                    }
                    putJsonObject("read") {
                        put("type", "boolean")
                        put("description", "true — пометить прочитанным (по умолчанию), false — непрочитанным")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("thread_id")) }
            },
        ),
        Tool(
            name = "subscription_list",
            description = "Список активных SIM-подписок устройства: id, слот, оператор, отображаемое имя и признак подписки по умолчанию для данных.",
            needsPermission = "android.permission.READ_PHONE_STATE",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "sms_send_on_sim",
            description = "Отправить SMS с выбранной SIM. number — получатель, text — текст, subscription_id — id подписки из subscription_list. Требует SEND_SMS и подтверждает отправку по результату от системы.",
            dangerous = true,
            needsPermission = "android.permission.SEND_SMS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("number") {
                        put("type", "string")
                        put("description", "Номер телефона получателя")
                    }
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "Текст сообщения")
                    }
                    putJsonObject("subscription_id") {
                        put("type", "string")
                        put("description", "Идентификатор SIM-подписки (id из subscription_list)")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("number"))
                    add(JsonPrimitive("text"))
                    add(JsonPrimitive("subscription_id"))
                }
            },
        ),
        Tool(
            name = "mms_send",
            description = "Отправить MMS на номер. Укажи content_uri (content:// медиа) или file_path. Требует SEND_SMS; приём и сохранение MMS доступны только приложению с ролью SMS по умолчанию.",
            dangerous = true,
            needsPermission = "android.permission.SEND_SMS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("number") {
                        put("type", "string")
                        put("description", "Номер телефона получателя")
                    }
                    putJsonObject("content_uri") {
                        put("type", "string")
                        put("description", "content:// URI отправляемого медиа (необязательно, если задан file_path)")
                    }
                    putJsonObject("file_path") {
                        put("type", "string")
                        put("description", "Путь к файлу (в общем хранилище; необязательно, если задан content_uri).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("number")) }
            },
        ),
        Tool(
            name = "sms_delete",
            description = "Удалить SMS. Удаление НЕОБРАТИМО: Undo не может восстановить SMS. " +
                "Работает только если Меха — приложение SMS по умолчанию (роль ROLE_SMS); иначе платформа " +
                "запрещает удаление, и инструмент честно об этом сообщит. scope: all — все SMS; " +
                "thread — только сообщения диалога (нужен thread_id из sms_conversations); " +
                "number — только сообщения с указанным номером (нужен number). " +
                "Для фактического удаления обязателен confirm=true; без confirm=true ничего не удаляется.",
            dangerous = true,
            needsPermission = "android.permission.READ_SMS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("scope") {
                        put("type", "string")
                        put("description", "Область удаления: all — все SMS; thread — сообщения одного диалога (нужен thread_id); number — сообщения с одним номером (нужен number)")
                    }
                    putJsonObject("thread_id") {
                        put("type", "string")
                        put("description", "Идентификатор диалога (из sms_conversations). Обязателен при scope=thread")
                    }
                    putJsonObject("number") {
                        put("type", "string")
                        put("description", "Номер телефона. Обязателен при scope=number")
                    }
                    putJsonObject("confirm") {
                        put("type", "boolean")
                        put("description", "true — подтверждение необратимого удаления. Без true удаление не выполняется")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("scope")) }
            },
        ),
    )
}
