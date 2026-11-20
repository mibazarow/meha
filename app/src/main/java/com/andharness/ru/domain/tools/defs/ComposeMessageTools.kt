package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- update.md §4.7: compose_message ----
internal object ComposeMessageTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "compose_message",
            description = "Написать/отправить сообщение человеку. to — имя человека, номер телефона " +
                "или ник; text — текст; channel — AUTO (по умолчанию), SMS или MESSENGER. Сначала " +
                "персона резолвится по персональной базе: при неоднозначности инструмент вернёт " +
                "кандидатов и попросит выбрать, НИЧЕГО не отправляя. Затем выбирается канал: явный " +
                "или AUTO (SMS при наличии телефона, иначе мессенджер). SMS отправляется через " +
                "системный SmsManager и результат подтверждается системой (нужно разрешение SEND_SMS). " +
                "Мессенджер: публичного API отправки нет, поэтому открывается приложение/выбор " +
                "приложений с предзаполненным текстом (ACTION_SEND, " +
                "https://developer.android.com/training/sharing/send) — это «открыто, но не " +
                "доставлено», о чём инструмент честно сообщает. После подтверждённой SMS-отправки в " +
                "персональную базу пишется взаимодействие (метаданные + gist, тело не хранится). " +
                "Опасное действие (отправка).",
            dangerous = true,
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("to") {
                        put("type", "string")
                        put(
                            "description",
                            "Получатель: имя/алиас человека, номер телефона или ник (обязательно)."
                        )
                    }
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "Текст сообщения (обязательно).")
                    }
                    putJsonObject("channel") {
                        put("type", "string")
                        put(
                            "description",
                            "Канал: AUTO (по умолчанию), SMS или MESSENGER."
                        )
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("to"))
                    add(JsonPrimitive("text"))
                }
            },
        ),
    )
}
