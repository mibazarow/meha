package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- update.md §1: автоматизация / сенсоры / UI / foreground ----
internal object AutomationSensorsUiTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "job_schedule",
            description = "Запланировать периодическую задачу через JobScheduler. interval_min — период в минутах, " +
                "минимум 15 (более частые периодические задачи система не поддерживает; меньшее значение будет " +
                "увеличено до 15). Необязательные requires_charging и requires_network задают условия запуска. " +
                "Укажите prompt (текст запроса — при срабатывании агент запустится в новом чате) или notify_text " +
                "(текст уведомления); хотя бы одно из них обязательно. Возвращает id для job_list / job_cancel. " +
                "Срабатывание неточное: система может задержать запуск, особенно в режиме Doze.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("interval_min") {
                        put("type", "string")
                        put("description", "Период в минутах, минимум 15 (например 30)")
                    }
                    putJsonObject("requires_charging") {
                        put("type", "boolean")
                        put("description", "Запускать только при подключённой зарядке (необязательно)")
                    }
                    putJsonObject("requires_network") {
                        put("type", "boolean")
                        put("description", "Запускать только при наличии сети (необязательно)")
                    }
                    putJsonObject("prompt") {
                        put("type", "string")
                        put("description", "Текст запроса агенту, который выполнится при срабатывании")
                    }
                    putJsonObject("notify_text") {
                        put("type", "string")
                        put("description", "Текст уведомления при срабатывании (если не нужен запуск агента)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("interval_min")) }
            },
        ),
        Tool(
            name = "job_list",
            description = "Показать задачи JobScheduler, запланированные этим приложением: id, период, условия запуска " +
                "и сохранённый текст (prompt или notify_text). Список читается напрямую у системы " +
                "(getAllPendingJobs) — это реальные ожидающие задачи, а не внутренний список приложения.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "job_cancel",
            description = "Отменить задачу JobScheduler по id и проверить, что она действительно исчезла из планировщика. " +
                "Удаляет и сохранённое описание задачи. Если задачи с таким id нет — честно сообщает об этом.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "id задачи из job_list или из ответа job_schedule")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("id")) }
            },
        ),
        Tool(
            name = "sensor_subscribe",
            description = "Собрать показания датчика за окно времени: подписка на SensorEventListener на duration_ms, " +
                "накопление отсчётов и статистика (min/max/avg по осям + последние значения). type — имя датчика " +
                "(accelerometer, gyroscope, magnetometer, light, proximity, pressure, gravity, linear_acceleration, " +
                "rotation_vector, step_counter, humidity, temperature). duration_ms по умолчанию 2000, максимум 10000; " +
                "rate: normal | fast | game. Слушатель всегда снимается по окончании окна. Если датчика нет — честный " +
                "отказ. Для step_counter/step_detector нужно разрешение ACTIVITY_RECOGNITION. Для частот выше 200 Гц " +
                "требуется HIGH_SAMPLING_RATE_SENSORS (объявлено; иначе система ограничивает частоту).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("type") {
                        put("type", "string")
                        put("description", "Имя датчика, например accelerometer или light")
                    }
                    putJsonObject("duration_ms") {
                        put("type", "string")
                        put("description", "Длительность окна в миллисекундах, 200–10000 (по умолчанию 2000)")
                    }
                    putJsonObject("rate") {
                        put("type", "string")
                        put("description", "Частота опроса: normal | fast | game (по умолчанию normal)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("type")) }
            },
        ),
        Tool(
            name = "sensor_unsubscribe",
            description = "Остановить активную подписку на датчик (если она ещё идёт) и снять слушатель. Если активной " +
                "подписки нет — честно сообщает об этом. Обычные окна sensor_subscribe завершаются сами, инструмент " +
                "нужен для досрочной остановки.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "sensor_trigger_subscribe",
            description = "Подписаться на trigger/wake-up датчик (например significant_motion) через requestTriggerSensor " +
                "и вернуть срабатывания за окно. type — имя датчика (significant_motion, step_detector и т.п.); " +
                "duration_ms по умолчанию 30000, максимум 300000. Подписка всегда отменяется по окончании окна. Если " +
                "датчик не является trigger/wake-up датчиком или отсутствует — честный отказ.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("type") {
                        put("type", "string")
                        put("description", "Имя trigger-датчика, например significant_motion")
                    }
                    putJsonObject("duration_ms") {
                        put("type", "string")
                        put("description", "Окно ожидания в миллисекундах, 1000–300000 (по умолчанию 30000)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("type")) }
            },
        ),
        Tool(
            name = "health_sensors_list",
            description = "Показать датчики здоровья, реально имеющиеся на устройстве: пульс (heart_rate), счётчик " +
                "шагов (step_counter), детектор шагов (step_detector), значимое движение (significant_motion), " +
                "акселерометр и вендорный датчик SpO₂ (если он есть). Для каждого датчика возвращает тип, имя, " +
                "производителя, максимальный диапазон, является ли он wake-up, и требуется ли разрешение " +
                "(BODY_SENSORS для пульса, ACTIVITY_RECOGNITION для шагов). Отсутствующие датчики честно " +
                "помечаются как недоступные; инструмент ничего не измеряет и не меняет.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "ui_gesture_multi",
            description = "Мультитач-жест (pinch/zoom) через спецвозможности: два одновременных штриха движутся " +
                "симметрично вокруг центра (cx, cy) с расстоянием start_distance до end_distance пикселей. " +
                "Инструмент ждёт подтверждения от системы. Требует включённого доступа спецвозможностей и " +
                "canPerformGestures=true; иначе честно сообщает об отказе.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("cx") {
                        put("type", "string")
                        put("description", "X-координата центра жеста в пикселях")
                    }
                    putJsonObject("cy") {
                        put("type", "string")
                        put("description", "Y-координата центра жеста в пикселях")
                    }
                    putJsonObject("start_distance") {
                        put("type", "string")
                        put("description", "Начальное расстояние между пальцами в пикселях")
                    }
                    putJsonObject("end_distance") {
                        put("type", "string")
                        put("description", "Конечное расстояние между пальцами в пикселях (меньше — сжатие, больше — разведение)")
                    }
                    putJsonObject("duration_ms") {
                        put("type", "string")
                        put("description", "Длительность жеста в миллисекундах, 50–3000 (по умолчанию 400)")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("cx"))
                    add(JsonPrimitive("cy"))
                    add(JsonPrimitive("start_distance"))
                    add(JsonPrimitive("end_distance"))
                }
            },
        ),
        Tool(
            name = "ui_trigger_subscribe",
            description = "Следить за событиями окон и смены приложения через спецвозможности и вернуть последние. " +
                "action: start (начать запись), stop (остановить запись, события сохраняются), list (вернуть " +
                "последние события: время, пакет, класс, тип). limit — сколько событий вернуть, по умолчанию 50 " +
                "(хранится до 200). Требует включённого доступа спецвозможностей; иначе честно сообщает об этом.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("action") {
                        put("type", "string")
                        put("description", "start | stop | list")
                    }
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько последних событий вернуть, 1–200 (по умолчанию 50)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("action")) }
            },
        ),
    )
}
