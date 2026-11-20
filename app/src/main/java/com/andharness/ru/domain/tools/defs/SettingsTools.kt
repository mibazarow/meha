package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- settings ----
internal object SettingsTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "settings_system_get",
            description = "Прочитать значение системной настройки (Settings.System) по ключу. Только чтение, разрешения не нужны; если ключ не найден или не задан — вернётся честное сообщение об этом, а не пустое значение.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("key") {
                        put("type", "string")
                        put("description", "Точное имя ключа Settings.System, напр. SCREEN_BRIGHTNESS, FONT_SCALE, TIME_12_24")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("key")) }
            },
        ),
        Tool(
            name = "settings_secure_get",
            description = "Прочитать значение защищённой настройки (Settings.Secure) по ключу. Только чтение, разрешения не нужны (запись Secure недоступна обычным приложениям); если ключ не найден или не задан — вернётся честное сообщение об этом.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("key") {
                        put("type", "string")
                        put("description", "Точное имя ключа Settings.Secure, напр. ANDROID_ID, DEFAULT_INPUT_METHOD")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("key")) }
            },
        ),
        Tool(
            name = "settings_global_get",
            description = "Прочитать значение глобальной настройки (Settings.Global) по ключу. Только чтение, разрешения не нужны (запись Global недоступна обычным приложениям); если ключ не найден или не задан — вернётся честное сообщение об этом.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("key") {
                        put("type", "string")
                        put("description", "Точное имя ключа Settings.Global, напр. AIRPLANE_MODE_ON, WIFI_ON, ADB_ENABLED")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("key")) }
            },
        ),
        Tool(
            name = "settings_known_keys",
            description = "Показать список часто используемых ключей Settings (System/Secure/Global) для справки.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "settings_system_set",
            description = "Записать значение в Settings.System (нужен доступ WRITE_SETTINGS). Меняет системную настройку устройства и выполняется сразу, без подтверждения: вызывай только по прямой просьбе пользователя. Инструмент проверяет доступ, записывает значение и честно сообщает, если ключ не удалось записать или он игнорируется производителем.",
            dangerous = true,
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("key") {
                        put("type", "string")
                        put("description", "Точное имя ключа Settings.System, напр. SCREEN_BRIGHTNESS, FONT_SCALE, TIME_12_24")
                    }
                    putJsonObject("value") {
                        put("type", "string")
                        put("description", "Значение для записи (строка; числа задавай как текст, напр. \"1\")")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("key"))
                    add(JsonPrimitive("value"))
                }
            },
        ),
        Tool(
            name = "settings_write_access_request",
            description = "Открыть системный экран «Изменение системных настроек», чтобы пользователь выдал приложению доступ WRITE_SETTINGS (нужен для settings_system_set). Разрешения не требует.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "dnd_set",
            description = "Включить/выключить режим «Не беспокоить» (нужен доступ к политике уведомлений). mode: off или all — выключить, priority — только приоритетные, alarms — только будильники, none — полный «не беспокоить». На Android 15 приложение влияет на режим через неявное правило, а система применяет самое строгое из активных правил — инструмент честно сообщает фактический итог.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("mode") {
                        put("type", "string")
                        put("description", "Один из: off, all, priority, alarms, none")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("mode")) }
            },
        ),
        Tool(
            name = "alarm_exact_set",
            description = "Поставить точный будильник/напоминание на конкретное время и сохранить его на устройстве. time — epoch-миллисекунды или время HH:MM (ближайшее наступление сегодня/завтра); прошедшее время отклоняется. Если точные будильники запрещены системой (SCHEDULE_EXACT_ALARM), инструмент честно сообщает, что срабатывание может быть неточным.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("time") {
                        put("type", "string")
                        put("description", "Время срабатывания: epoch-миллисекунды (напр. 1735689600000) или HH:MM (напр. 07:30)")
                    }
                    putJsonObject("label") {
                        put("type", "string")
                        put("description", "Подпись будильника (опционально)")
                    }
                    putJsonObject("chat_id") {
                        put("type", "string")
                        put("description", "ID чата для перехода по уведомлению (опционально; по умолчанию — текущий чат)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("time")) }
            },
        ),
        Tool(
            name = "alarm_exact_list",
            description = "Показать сохранённые точные будильники: id, время, подпись и сработал ли он.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "alarm_exact_cancel",
            description = "Отменить и удалить сохранённый точный будильник по id (см. alarm_exact_list).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "ID будильника из alarm_exact_list")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("id")) }
            },
        ),
    )
}
