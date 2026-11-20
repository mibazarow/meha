package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- notifications (listener) ----
internal object NotificationsTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "notification_list",
            description = "Список активных уведомлений (пакет, заголовок, текст, key). Требует включённого доступа к уведомлениям.",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "notification_open",
            description = "Открыть уведомление (перейти в приложение) по key из notification_list.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("key") { put("type", "string"); put("description", "key уведомления") } }
                putJsonArray("required") { add(JsonPrimitive("key")) }
            },
        ),
        Tool(
            name = "notification_dismiss",
            description = "Снять (удалить) уведомление по key из notification_list.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("key") { put("type", "string"); put("description", "key уведомления") } }
                putJsonArray("required") { add(JsonPrimitive("key")) }
            },
        ),
        Tool(
            name = "notification_reply",
            description = "Ответить на уведомление (если есть поле быстрого ответа) по key из notification_list.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("key") { put("type", "string"); put("description", "key уведомления") }
                    putJsonObject("text") { put("type", "string"); put("description", "Текст ответа") }
                }
                putJsonArray("required") { add(JsonPrimitive("key")); add(JsonPrimitive("text")) }
            },
        ),
        Tool(
            name = "notification_clear_all",
            description = "Снять (закрыть) все активные уведомления. Требует включённого доступа к уведомлениям. Честно сообщает, сколько уведомлений снять не удалось (например, постоянные/неубираемые).",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "notification_channels_list",
            description = "Список каналов уведомлений приложения и их важность.",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "notification_channels_create",
            description = "Создать или обновить канал уведомлений приложения. Важность уже созданного канала нельзя повысить, если пользователь понизил её сам, — инструмент честно сообщит об этом.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") { put("type", "string"); put("description", "Идентификатор канала, например reminders") }
                    putJsonObject("name") { put("type", "string"); put("description", "Отображаемое имя канала") }
                    putJsonObject("importance") { put("type", "string"); put("description", "Важность: low, default или high (по умолчанию default)") }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("id"))
                    add(JsonPrimitive("name"))
                }
            },
        ),
        Tool(
            name = "dnd_status",
            description = "Проверить, выдан ли приложению доступ к политике уведомлений (режим «Не беспокоить»), и текущий фильтр.",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "app_usage",
            description = "Статистика использования приложений (экранное время) за N дней. Требует доступа к данным об использовании.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("days") { put("type", "string"); put("description", "Число дней 1–30 (по умолчанию 1)") } }
            },
        ),
        Tool(
            name = "device_lock",
            description = "Заблокировать экран устройства. Требует активации Меха как администратора устройства.",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "device_admin_enable",
            description = "Открыть экран активации Меха как администратора устройства (нужно для блокировки экрана).",
            parameters = buildJsonObject { put("type", "object") },
        ),

        Tool(
            name = "speech_to_text",
            description = "Распознать речь с ЖИВОГО микрофона в текст (диктовка). Можно указать language (BCP-47, напр. ru-RU) и timeout_ms. Готовые аудиофайлы этим инструментом не распознаются — для файлов используй recording_import или recordings_scan (расшифровка на устройстве через whisper).",
            needsPermission = "android.permission.RECORD_AUDIO",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("language") { put("type", "string"); put("description", "Язык, напр. ru-RU (опционально)") }
                    putJsonObject("timeout_ms") { put("type", "string"); put("description", "Таймаут ожидания, мс (2000–30000)") }
                }
            },
        ),
        Tool(
            name = "ocr_image",
            description = "Распознать текст на изображении (локально, офлайн — Tesseract с кириллицей " +
                "и латиницей; при сбое движка — латинский ML Kit). " +
                "path — путь к картинке.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("path") { put("type", "string"); put("description", "Путь к изображению") } }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "vision_status",
            description = "Показать, принимает ли текущая настроенная LLM изображения и почему так решено " +
                "(allowlist известных vision-моделей, сохранённый результат пробы, свежая проба или «неизвестно»). " +
                "Честно сообщает «неизвестно», если эндпоинт недоступен. Без Vision используй ocr_image.",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "vision_check",
            description = "Принудительно перепроверить поддержку изображений у текущей модели: отправляет " +
                "крошечную пробную картинку на настроенный эндпоинт (с ограничением по времени) и сохраняет " +
                "результат. Используй после смены модели или провайдера.",
            parameters = buildJsonObject { put("type", "object") },
        ),

        Tool(
            name = "tool_search",
            description = "Найти инструменты по текстовому запросу на русском или английском " +
                "(например «отправить смс», «курс доллара», «прочитать pdf»). Возвращает имена " +
                "подходящих инструментов и краткое описание. Используй, когда нужного инструмента " +
                "нет в текущем наборе, — так можно найти и вызвать любой доступный инструмент.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Что нужно сделать, обычными словами (русский или английский)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("query")) }
            },
        ),

        Tool(
            name = "selftest_run",
            description = "Самопроверка: прогоняет все НЕинтерактивные инструменты с безопасными аргументами и возвращает отчёт (успешно/сбой по каждому). Используй, когда просят проверить работоспособность инструментов.",
            parameters = buildJsonObject { put("type", "object") },
        ),
    )
}
