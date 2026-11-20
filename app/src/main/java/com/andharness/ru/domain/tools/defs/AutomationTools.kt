package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- automation (Tasker-like "when trigger -> do action" rules) ----
internal object AutomationTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "automation_rule_create",
            description = "Создать правило автоматизации «когда ПРОИСХОДИТ триггер → выполнить действие». " +
                "trigger_type: time (trigger_config = «HH:MM», срабатывает неточно через WorkManager), " +
                "charging, battery_low, screen_on, screen_off (работают только пока жив процесс приложения), " +
                "boot (после перезагрузки), wifi_connected, network (при появлении сети), " +
                "app_opened (trigger_config = имя пакета, нужен включённый доступ спецвозможностей), " +
                "notification (trigger_config = имя пакета или подстрока текста, нужен доступ к уведомлениям), " +
                "sms (trigger_config = «from=подстрока отправителя; text_contains=подстрока текста» — оба фильтра " +
                "необязательны и без учёта регистра, без них срабатывает на любое входящее SMS; нужен доступ " +
                "RECEIVE_SMS). " +
                "Глубокие триггеры: calendar_event (trigger_config = «title_contains=…; minutes_before=5», " +
                "проверка календаря раз в ~15 мин через WorkManager, нужно READ_CALENDAR), " +
                "ble_presence (trigger_config = «address=MAC» или " +
                "«name_contains=…», необязательно «; rssi_threshold=-70 (от -100 до 0 dBm); timeout_sec=15»), " +
                "sound_event (trigger_config = «level_threshold=-20 (от -90 до 0 dBFS); sustain_ms=500» — " +
                "устойчиво громкий звук), geo_distance (trigger_config = «distance_m=300 (от 10 до 1000000 м); " +
                "min_interval_sec=600 (от 60 до 86400)» — срабатывает, когда телефон сместился от последней " +
                "сохранённой точки; нужен ACCESS_FINE_LOCATION, работает только пока жив процесс), " +
                "wifi_open (trigger_config = «ssid_contains=…; min_interval_sec=600 (от 60 до 86400)» — обе " +
                "опции необязательны: без ssid_contains срабатывает на любую ОТКРЫТУЮ сеть (без шифрования), " +
                "повторно одна и та же сеть не срабатывает; нужен NEARBY_WIFI_DEVICES (API 31+) или " +
                "ACCESS_FINE_LOCATION, работает только пока жив процесс). " +
                "Триггеры здоровья: heart_rate_threshold (trigger_config = «above=100» или «below=50» " +
                "(bpm 20–250, строгое сравнение) и необязательный «; dwell_sec=10» (0–3600 с) — пульс выше/ниже " +
                "порога; нужен датчик TYPE_HEART_RATE и разрешение BODY_SENSORS), " +
                "steps_reached (trigger_config = «target=5000», шагов 1–1000000 — дельта накопительного " +
                "TYPE_STEP_COUNTER от момента подписки; нужно ACTIVITY_RECOGNITION), " +
                "no_movement (trigger_config = «minutes=30», 1–1440 — нет значимого движения; источник — " +
                "TYPE_SIGNIFICANT_MOTION или акселерометр), " +
                "sleep_window (trigger_config = «start=23:00; end=07:00» — ЧЕСТНАЯ ЭВРИСТИКА: окно времени + " +
                "экран выключен + телефон неподвижен; в Android нет публичного датчика фаз сна, это НЕ измерение сна). " +
                "Если подходящего датчика на устройстве нет, инструмент честно отказывает. " +
                "action_type: start_chat (action_config = текст запроса агенту), " +
                "agent_task (action_config = ШАБЛОН запроса, который заполняется данными сработавшего " +
                "события: {trigger} — тип события, {value} — значение события, {payload} — payload " +
                "события, {name} — имя правила; отсутствующие значения становятся пустыми, неизвестные " +
                "подстановки остаются как есть) или notify " +
                "(action_config = «заголовок | текст», необязательно «| chatId»). Инструмент честно отказывает при неверном конфиге.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Название правила, например «Зарядка включена»")
                    }
                    putJsonObject("trigger_type") {
                        put("type", "string")
                        put("description", "Тип триггера (полный список — в описании инструмента).")
                    }
                    putJsonObject("trigger_config") {
                        put("type", "string")
                        put("description", "Настройка триггера (формат — по типу, см. описание инструмента).")
                    }
                    putJsonObject("action_type") {
                        put("type", "string")
                        put("description", "start_chat, agent_task или notify (см. описание инструмента).")
                    }
                    putJsonObject("action_config") {
                        put("type", "string")
                        put("description", "Настройка действия (формат — по типу, см. описание инструмента).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("name"))
                    add(JsonPrimitive("trigger_type"))
                    add(JsonPrimitive("action_type"))
                }
            },
        ),
        Tool(
            name = "automation_rule_list",
            description = "Показать все правила автоматизации: id, статус (вкл/выкл), триггер и действие, время последнего срабатывания.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "automation_rule_toggle",
            description = "Включить или выключить правило автоматизации по id (см. automation_rule_list), не удаляя его. Для выключения/включения time-правила перепланируется.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "ID правила из automation_rule_list")
                    }
                    putJsonObject("enabled") {
                        put("type", "boolean")
                        put("description", "true — включить, false — выключить")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("id"))
                    add(JsonPrimitive("enabled"))
                }
            },
        ),
        Tool(
            name = "automation_rule_delete",
            description = "Удалить правило автоматизации по id (см. automation_rule_list).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "ID правила из automation_rule_list")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("id")) }
            },
        ),
        Tool(
            name = "events_recent",
            description = "Показать последние сработавшие события автоматизации (журнал «что было ночью»): " +
                "время · тип триггера · правило · значение. Хранятся только события, которые реально " +
                "запустили правило (не сырые показания датчиков); журнал ограничен последними 500 событиями " +
                "за 14 дней. Необязательные параметры: limit (1–100, по умолчанию 20) и type " +
                "(фильтр по типу триггера, например battery_low).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько событий вернуть (1–100, по умолчанию 20)")
                    }
                    putJsonObject("type") {
                        put("type", "string")
                        put("description", "Фильтр по типу триггера (значения — как в automation_rule_create).")
                    }
                }
            },
        ),
        Tool(
            name = "automation_suggestions",
            description = "Предиктивные подсказки автоматизаций по реальному использованию приложений " +
                "(UsageStats за последние 14 дней). Возвращает устойчивые шаблоны — какое приложение вы обычно " +
                "открываете в какое время, а также эвристику «ночной режим» — с оценкой уверенности и уже готовой " +
                "конфигурацией правила (trigger time + action notify). Требует спец-доступа «Доступ к данным об " +
                "использовании» (PACKAGE_USAGE_STATS); без него честно сообщает, что доступ не выдан. Принятые и " +
                "скрытые подсказки больше не повторяются. Чтобы принять подсказку, создайте правило через automation_rule_create.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько подсказок вернуть (1–20, по умолчанию 10)")
                    }
                }
            },
        ),
        Tool(
            name = "automation_suggestions_simulate",
            description = "СЛУЖЕБНЫЙ инструмент только для debug-сборки: прогоняет синтетическую историю " +
                "использования (5 из 7 дней) через настоящий анализатор подсказок. action: analyze — показать " +
                "найденные подсказки, accept — принять первую (или указанную id) и создать реальное правило, " +
                "dismiss — скрыть. В release-сборке честно отказывает. Нужен для проверки на устройстве без " +
                "реальной истории.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("action") {
                        put("type", "string")
                        put("description", "analyze | accept | dismiss (по умолчанию analyze)")
                    }
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "id подсказки; по умолчанию первая найденная")
                    }
                }
            },
        ),
    )
}
