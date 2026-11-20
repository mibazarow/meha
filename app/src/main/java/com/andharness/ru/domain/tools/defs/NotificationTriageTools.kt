package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- notification triage / digest (update.md idea #17) ----
internal object NotificationTriageTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "notifications_digest",
            description = "Сводка недавно захваченных уведомлений (SMS/мессенджеры/почта) по приложениям, " +
                "важные отмечены. Работает для пакетов из messaging_index_config при выданном доступе " +
                "к уведомлениям. since — сколько минут назад (или метка времени в мс), " +
                "limit — сколько записей. Если захватов нет, честно сообщает об этом.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("since") {
                        put("type", "string")
                        put("description", "Окно в минутах (например 60) или метка времени в мс; необязательно")
                    }
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько последних уведомлений показать, 1–200 (по умолчанию 30)")
                    }
                }
                putJsonArray("required") { }
            },
        ),
        Tool(
            name = "notification_triage_simulate",
            description = "Служебный (только debug): прогнать синтетическое уведомление через триаж и вернуть " +
                "важность, категорию, сводку и признак записи в память. Нужен для проверки без сторонних " +
                "приложений; в релизной сборке отказывает.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("package") { put("type", "string"); put("description", "Имя пакета-источника") }
                    putJsonObject("title") { put("type", "string"); put("description", "Заголовок уведомления") }
                    putJsonObject("text") { put("type", "string"); put("description", "Текст уведомления") }
                    putJsonObject("sender") { put("type", "string"); put("description", "Отправитель/чат (необязательно)") }
                    putJsonObject("group") { put("type", "boolean"); put("description", "Это группа/рассылка?") }
                    putJsonObject("missed_call") { put("type", "boolean"); put("description", "Это пропущенный звонок?") }
                }
                putJsonArray("required") { add(JsonPrimitive("package")) }
            },
        ),
        Tool(
            name = "notification_rules_list",
            description = "Показать пользовательские правила уведомлений Меха: важность по каждому " +
                "приложению (без уведомлений / обычно / важное), тихие часы и режим сводки. " +
                "Только чтение — ничего не меняет. Не путать с системными каналами уведомлений " +
                "(для них notification_channels_list).",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "notification_rules_set",
            description = "Задать или обновить правила уведомлений Меха. Все параметры необязательные: " +
                "package + importance (+ note) задают правило приложения; quiet_start и quiet_end " +
                "включают тихие часы (ЧЧ:ММ, окно может переходить через полночь); quiet_off выключает " +
                "их; quiet_allow_important=true разрешает важным уведомлениям не молчать в тихие часы; " +
                "summary_mode управляет сводкой (off / important_only / all); remove=true удаляет правило " +
                "приложения (или все правила, если package не указан). Меха не трогает системные " +
                "уведомления: правило «без уведомлений» лишь перестаёт показывать их в сводке и памяти Меха.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("package") {
                        put("type", "string")
                        put("description", "Пакет приложения, например org.telegram.messenger")
                    }
                    putJsonObject("importance") {
                        put("type", "string")
                        put("description", "Важность: mute (без уведомлений), normal (обычно), important (важное)")
                    }
                    putJsonObject("note") {
                        put("type", "string")
                        put("description", "Заметка к правилу приложения, необязательно (до 200 символов)")
                    }
                    putJsonObject("remove") {
                        put("type", "boolean")
                        put("description", "Удалить правило приложения; без package — удалить все правила приложений")
                    }
                    putJsonObject("quiet_start") {
                        put("type", "string")
                        put("description", "Начало тихих часов ЧЧ:ММ, например 23:00")
                    }
                    putJsonObject("quiet_end") {
                        put("type", "string")
                        put("description", "Конец тихих часов ЧЧ:ММ, например 07:00 (может быть меньше начала — окно через полночь)")
                    }
                    putJsonObject("quiet_off") {
                        put("type", "boolean")
                        put("description", "Выключить тихие часы")
                    }
                    putJsonObject("quiet_allow_important") {
                        put("type", "boolean")
                        put("description", "Разрешать важным уведомлениям не молчать в тихие часы")
                    }
                    putJsonObject("summary_mode") {
                        put("type", "string")
                        put("description", "Режим сводки: off (выключена), important_only (только важное), all (все)")
                    }
                }
                putJsonArray("required") { }
            },
        ),
        Tool(
            name = "sms_photo_rule_get",
            description = "Показать настройку фото-отчёта по SMS: включён ли он, префикс ответа и кулдаун " +
                "на номер. Только чтение — ничего не меняет. Когда включено, Меха отвечает фотографией " +
                "(с описанием) только на команды с ДОВЕРЕННЫХ номеров: «фото», «сделай фото», " +
                "«что на фото», «снимок», «photo», «picture», «take a photo», «what do you see».",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "sms_photo_rule_set",
            description = "Включить/выключить фото-отчёт по SMS и настроить его. enabled — включён ли " +
                "отчёт (по умолчанию ВЫКЛЮЧЕН); reply_text_prefix — текст-префикс ответа (необязательно); " +
                "cooldown_seconds — минимальный интервал между ответами одному номеру в секундах " +
                "(по умолчанию 60, 0 — без кулдауна). Отвечает только доверенным номерам " +
                "(trusted_source_add type=sms) и никогда — служебным, коротким и экстренным номерам. " +
                "Когда Vision у модели нет, приходит честный офлайн-OCR с пометкой, что изображение не отправлено модели.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("enabled") {
                        put("type", "boolean")
                        put("description", "Включить фото-отчёт по SMS (по умолчанию false)")
                    }
                    putJsonObject("reply_text_prefix") {
                        put("type", "string")
                        put("description", "Префикс ответа, например «Фото: » (необязательно, до 200 символов)")
                    }
                    putJsonObject("cooldown_seconds") {
                        put("type", "string")
                        put("description", "Кулдаун на номер в секундах, 0..86400 (по умолчанию 60)")
                    }
                }
                putJsonArray("required") { }
            },
        ),
    )
}
