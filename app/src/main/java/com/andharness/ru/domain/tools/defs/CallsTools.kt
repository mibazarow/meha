package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- calls ----
internal object CallsTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "call_log",
            description = "Список звонков из журнала (тип, номер, дата, длительность). Передай limit для ограничения числа записей. Опциональные фильтры объединяются по И: number (по номеру, с учётом форматирования), type (incoming, outgoing, missed, rejected) и days (последние N дней). Без фильтров — самые свежие записи.",
            needsPermission = "android.permission.READ_CALL_LOG",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Максимальное количество возвращаемых записей")
                    }
                    putJsonObject("number") {
                        put("type", "string")
                        put("description", "Фильтр по номеру телефона (учитывает формат: 8…, +7…, пробелы)")
                    }
                    putJsonObject("type") {
                        put("type", "string")
                        put("description", "Фильтр по типу звонка: incoming, outgoing, missed или rejected")
                    }
                    putJsonObject("days") {
                        put("type", "string")
                        put("description", "Фильтр по времени: только звонки за последние N дней")
                    }
                }
            },
        ),
        Tool(
            name = "call_start",
            description = "Совершить голосовой вызов на указанный номер.",
            dangerous = true,
            needsPermission = "android.permission.CALL_PHONE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("phone") {
                        put("type", "string")
                        put("description", "Номер телефона вызова")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("phone")) }
            },
        ),
        Tool(
            name = "ussd_request",
            description = "Выполнить USSD/сервисный код (например *#06# или *100#) и вернуть ответ " +
                "сети. Честный фолбэк: на Android 11+ прямой USSD фактически работает только у " +
                "приложения-дозвонщика по умолчанию, поэтому при неудаче/таймауте инструмент НЕ " +
                "выдумывает ответ и НЕ звонит сам — он открывает дозвонщик с готовым кодом " +
                "(ACTION_DIAL), чтобы вы нажали «Вызов» и прочитали ответ сети, либо набрали номер " +
                "вручную. Разрешение CALL_PHONE требуется только для прямой попытки.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("code") {
                        put("type", "string")
                        put("description", "USSD/сервисный код, например *#06# или *100#")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("code")) }
            },
        ),
        Tool(
            name = "blocked_number_ui",
            description = "Открыть системный экран блокировки номеров. number — необязательный номер, который нужно добавить вручную. Запись системного списка заблокированных номеров обычному приложению недоступна.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("number") {
                        put("type", "string")
                        put("description", "Номер, который нужно добавить на системном экране (необязательно)")
                    }
                }
            },
        ),
        Tool(
            name = "call_observe_status",
            description = "Состояние вызова: идёт ли сейчас звонок (TelecomManager.isInCall) и есть ли управляемый вызов (isInManagedCall, API 31+). Номер вызывающего не раскрывается.",
            needsPermission = "android.permission.READ_PHONE_STATE",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "call_rules_list",
            description = "Показать правила авто-обработки звонков/SMS: включена ли обработка (по умолчанию выключена), блокировка незнакомых, список заблокированных и разрешённых номеров, авто-ответ SMS и напоминание перезвонить. Только чтение.",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "call_rules_set",
            description = "Настроить авто-обработку звонков/SMS (opt-in, по умолчанию выключена). Все параметры необязательны: enabled (главный выключатель), block_unknown (отклонять незнакомые номера), blocked_numbers (номера через запятую), allow_numbers (белый список — всегда пропускает), auto_reply_sms (авто-ответ SMS незнакомым), auto_reply_text (текст ответа), auto_reply_when_busy (авто-ответ при занятости), call_back_reminder (напоминание перезвонить известному контакту). Правила хранятся локально и относятся только к собственной политике Меха; системный список заблокированных номеров не изменяется, короткие, сервисные и экстренные номера авто-ответом не затрагиваются.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("enabled") {
                        put("type", "boolean")
                        put("description", "Главный выключатель авто-обработки. По умолчанию false — ничего не обрабатывается автоматически.")
                    }
                    putJsonObject("block_unknown") {
                        put("type", "boolean")
                        put("description", "Отклонять звонки с номеров, которых нет в контактах.")
                    }
                    putJsonObject("blocked_numbers") {
                        put("type", "string")
                        put("description", "Номера для отклонения, через запятую (например +79001234567, +79007654321).")
                    }
                    putJsonObject("allow_numbers") {
                        put("type", "string")
                        put("description", "Белый список номеров через запятую: они всегда пропускаются и никогда не получают авто-ответ.")
                    }
                    putJsonObject("auto_reply_sms") {
                        put("type", "boolean")
                        put("description", "Автоматически отвечать SMS незнакомым номерам (не чаще одного раза на номер за 6 часов).")
                    }
                    putJsonObject("auto_reply_text") {
                        put("type", "string")
                        put("description", "Текст авто-ответа (до 300 символов).")
                    }
                    putJsonObject("auto_reply_when_busy") {
                        put("type", "boolean")
                        put("description", "Присылать авто-ответ, когда идёт другой звонок (занято).")
                    }
                    putJsonObject("call_back_reminder") {
                        put("type", "boolean")
                        put("description", "Создавать напоминание перезвонить, если звонок от известного контакта отклонён или пропущен.")
                    }
                }
            },
        ),
    )
}
