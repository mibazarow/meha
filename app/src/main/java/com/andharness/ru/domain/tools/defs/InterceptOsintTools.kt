package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- update.md §2: interception + OSINT ----
internal object InterceptOsintTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "number_osint",
            description = "Собрать открытую информацию о номере телефона через бесплатный веб-поиск " +
                "(без ключей и платных API): бюджет 15 секунд, не более 5 источников. Результат " +
                "кэшируется по номеру и не перезапрашивается, пока свежий (7 дней). Если ничего не " +
                "найдено — честно сообщает об этом и НИЧЕГО не выдумывает. Заодно создаёт/обновляет " +
                "авто-чат этого номера и отправляет уведомление со ссылкой на чат. force=true — " +
                "принудительно перезапросить, игнорируя кэш.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("number") {
                        put("type", "string")
                        put("description", "Номер телефона в любом формате (+7…, 8…, 10 цифр).")
                    }
                    putJsonObject("force") {
                        put("type", "boolean")
                        put("description", "Перезапросить, игнорируя свежий кэш (по умолчанию false).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("number")) }
            },
        ),
        Tool(
            name = "number_profile",
            description = "Локальный профиль номера: нормализованный вид, страна/регион по префиксу " +
                "(эвристика) и найден ли контакт. Оператор и тип линии по чужому номеру локально не " +
                "выводимы — инструмент честно сообщает это и не выдумывает данные.",
            needsPermission = "android.permission.READ_PHONE_STATE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("number") {
                        put("type", "string")
                        put("description", "Номер телефона в любом формате.")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("number")) }
            },
        ),
        Tool(
            name = "auto_number_chat_status",
            description = "Статус авто-чата незнакомых номеров: включён ли перехват, выдана ли роль " +
                "CALL_SCREENING (без неё реального перехвата звонков нет — только фолбэк по журналу " +
                "вызовов с задержкой до 15 минут), запущено ли ядро, сколько номеров и записей OSINT в кэше.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "auto_number_chat_toggle",
            description = "Включить/выключить авто-чат незнакомых номеров и перехват звонков/SMS. " +
                "Управляет сохранённым флагом перехвата: при включении также запускает фоновое ядро и " +
                "ставит периодическое наблюдение за журналом вызовов (каждые 15 минут, результат с " +
                "задержкой). Вызовы никогда не блокируются.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("enabled") {
                        put("type", "boolean")
                        put("description", "true — включить перехват и авто-чат; false — выключить.")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("enabled")) }
            },
        ),
        Tool(
            name = "unknown_numbers_list",
            description = "Список незнакомых номеров: для каждого — созданный чат, тип последнего события " +
                "и время, плюс незнакомые номера из журнала вызовов, для которых авто-чат ещё не создан. " +
                "Честно указывает, включён ли перехват и выдана ли роль CALL_SCREENING.",
            needsPermission = "android.permission.READ_CALL_LOG",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько записей показать, 1–100 (по умолчанию 50)")
                    }
                }
            },
        ),
    )
}
