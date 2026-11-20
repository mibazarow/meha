package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- location ----
internal object LocationTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "location_get",
            description = "Получить текущие координаты устройства (широта/долгота) через GPS/сетевой/пассивный провайдеры.",
            needsPermission = "android.permission.ACCESS_FINE_LOCATION",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "location_last_known",
            description = "Последняя известная локация (без запроса обновлений). Читает сохранённый фикс GPS/сети; если его нет — честно сообщает об этом.",
            needsPermission = "android.permission.ACCESS_FINE_LOCATION",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "location_updates_start",
            description = "Начать поток обновлений местоположения. Запускает foreground-службу типа location с LocationManager.requestLocationUpdates и копит точки (история ограничена). Для фоновых обновлений дополнительно нужно разрешение «Местоположение всегда» (ACCESS_BACKGROUND_LOCATION).",
            needsPermission = "android.permission.ACCESS_FINE_LOCATION",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("interval_sec") {
                        put("type", "string")
                        put("description", "Интервал обновлений в секундах (по умолчанию 30, минимум 5)")
                    }
                    putJsonObject("min_distance_m") {
                        put("type", "string")
                        put("description", "Минимальное смещение в метрах для новой точки (по умолчанию 10)")
                    }
                    putJsonObject("high_accuracy") {
                        put("type", "boolean")
                        put("description", "Высокая точность (GPS); по умолчанию false — сетевой провайдер")
                    }
                }
            },
        ),
        Tool(
            name = "location_updates_stop",
            description = "Остановить поток обновлений местоположения и отписаться от провайдера.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "location_updates_get",
            description = "Последние полученные точки локации. Возвращает координаты, точность и время (новые сверху).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько последних точек вернуть (по умолчанию 20)")
                    }
                }
            },
        ),
        Tool(
            name = "geofence_add",
            description = "Добавить геозону. При входе/выходе (и опционально пребывании) придёт уведомление. Требуются разрешения ACCESS_FINE_LOCATION и ACCESS_BACKGROUND_LOCATION; лимит ~100 геозон.",
            needsPermission = "android.permission.ACCESS_BACKGROUND_LOCATION",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("latitude") {
                        put("type", "string")
                        put("description", "Широта центра геозоны (градусы)")
                    }
                    putJsonObject("longitude") {
                        put("type", "string")
                        put("description", "Долгота центра геозоны (градусы)")
                    }
                    putJsonObject("radius_m") {
                        put("type", "string")
                        put("description", "Радиус геозоны в метрах (больше 0)")
                    }
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "Идентификатор геозоны (необязательно; иначе будет сгенерирован)")
                    }
                    putJsonObject("expiration_hours") {
                        put("type", "string")
                        put("description", "Срок жизни геозоны в часах (необязательно; иначе бессрочная)")
                    }
                    putJsonObject("dwell") {
                        put("type", "boolean")
                        put("description", "Сообщать о пребывании внутри зоны (dwell); по умолчанию false")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("latitude"))
                    add(JsonPrimitive("longitude"))
                    add(JsonPrimitive("radius_m"))
                }
            },
        ),
        Tool(
            name = "geofence_remove",
            description = "Удалить геозону по её идентификатору.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "Идентификатор геозоны, которую нужно удалить")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("id")) }
            },
        ),
        Tool(
            name = "geofence_list",
            description = "Список геозон, зарегистрированных приложением: центр, радиус, срок и последнее событие.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "calendar_events",
            description = "Список событий календаря в диапазоне [from, to] (from/to — epoch millis или ISO-дата). Возвращает id, название, начало, конец.",
            needsPermission = "android.permission.READ_CALENDAR",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("from") {
                        put("type", "string")
                        put("description", "Начало диапазона (epoch millis или ISO-дата)")
                    }
                    putJsonObject("to") {
                        put("type", "string")
                        put("description", "Конец диапазона (epoch millis или ISO-дата)")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("from"))
                    add(JsonPrimitive("to"))
                }
            },
        ),
        Tool(
            name = "calendar_list",
            description = "Список календарей устройства: id, название, аккаунт, цвет, видимость.",
            needsPermission = "android.permission.READ_CALENDAR",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "calendar_instances",
            description = "События (вхождения) календаря за период. from/to — epoch millis или ISO-дата. Возвращает название, начало, конец и календарь.",
            needsPermission = "android.permission.READ_CALENDAR",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("from") {
                        put("type", "string")
                        put("description", "Начало периода (epoch millis или ISO-дата)")
                    }
                    putJsonObject("to") {
                        put("type", "string")
                        put("description", "Конец периода (epoch millis или ISO-дата)")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("from"))
                    add(JsonPrimitive("to"))
                }
            },
        ),
        Tool(
            name = "calendar_accounts_list",
            description = "Список календарных аккаунтов и календарей устройства (Google, Microsoft, локальные) через CalendarContract: имя и тип аккаунта, название календаря, видимость, синхронизация событий, уровень доступа. Синхронизацию провайдеров выполняют системные приложения календаря — приложение лишь читает их. Если аккаунтов нет — честно сообщает об этом.",
            needsPermission = "android.permission.READ_CALENDAR",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "calendar_device_events",
            description = "События по всем календарям устройства (Google, Microsoft, локальные) за диапазон [from, to] (epoch millis или ISO-дата), с повторами. Для каждого события возвращает название, начало, конец, календарь и аккаунт. Диапазон ограничен (не более ~400 дней). Если событий нет — честный пустой результат.",
            needsPermission = "android.permission.READ_CALENDAR",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("from") {
                        put("type", "string")
                        put("description", "Начало диапазона (epoch millis или ISO-дата)")
                    }
                    putJsonObject("to") {
                        put("type", "string")
                        put("description", "Конец диапазона (epoch millis или ISO-дата)")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("from"))
                    add(JsonPrimitive("to"))
                }
            },
        ),
        Tool(
            name = "calendar_create",
            description = "Создать событие календаря с названием, началом, концом и (опционально) напоминанием за N минут до начала.",
            dangerous = true,
            needsPermission = "android.permission.WRITE_CALENDAR",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("title") {
                        put("type", "string")
                        put("description", "Название события")
                    }
                    putJsonObject("start") {
                        put("type", "string")
                        put("description", "Начало события (epoch millis или ISO-дата)")
                    }
                    putJsonObject("end") {
                        put("type", "string")
                        put("description", "Конец события (epoch millis или ISO-дата)")
                    }
                    putJsonObject("reminder") {
                        put("type", "string")
                        put("description", "Напоминание за N минут до начала события")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("title"))
                    add(JsonPrimitive("start"))
                    add(JsonPrimitive("end"))
                }
            },
        ),
        Tool(
            name = "calendar_update",
            description = "Обновить событие календаря по id (id из calendar_events). Передай только изменяемые поля: title, start, end, reminder.",
            dangerous = true,
            needsPermission = "android.permission.WRITE_CALENDAR",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "Событие (id из calendar_events)")
                    }
                    putJsonObject("title") {
                        put("type", "string")
                        put("description", "Новое название события (опционально)")
                    }
                    putJsonObject("start") {
                        put("type", "string")
                        put("description", "Новое начало (epoch millis или ISO-дата, опционально)")
                    }
                    putJsonObject("end") {
                        put("type", "string")
                        put("description", "Новый конец (epoch millis или ISO-дата, опционально)")
                    }
                    putJsonObject("reminder") {
                        put("type", "string")
                        put("description", "Новое напоминание за N минут до начала (опционально)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("id")) }
            },
        ),
        Tool(
            name = "calendar_delete",
            description = "Удалить событие календаря по id (id из calendar_events). Необратимо, без подтверждения — только по прямой просьбе пользователя.",
            dangerous = true,
            needsPermission = "android.permission.WRITE_CALENDAR",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "Идентификатор удаляемого события (id из calendar_events)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("id")) }
            },
        ),
        Tool(
            name = "calendar_attendees_set",
            description = "Добавить участника к событию календаря. event_id — id события, email — адрес участника, name — имя (необязательно). Действие изменяет данные календаря.",
            dangerous = true,
            needsPermission = "android.permission.WRITE_CALENDAR",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("event_id") {
                        put("type", "string")
                        put("description", "Событие (id из calendar_events)")
                    }
                    putJsonObject("email") {
                        put("type", "string")
                        put("description", "Email участника")
                    }
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Имя участника (необязательно)")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("event_id"))
                    add(JsonPrimitive("email"))
                }
            },
        ),
        Tool(
            name = "calendar_reminders_set",
            description = "Добавить напоминание к событию календаря. event_id — id события, minutes — за сколько минут до начала (можно отрицательное). Действие изменяет данные календаря.",
            dangerous = true,
            needsPermission = "android.permission.WRITE_CALENDAR",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("event_id") {
                        put("type", "string")
                        put("description", "Событие (id из calendar_events)")
                    }
                    putJsonObject("minutes") {
                        put("type", "string")
                        put("description", "Число минут до начала события (можно отрицательное — после начала)")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("event_id"))
                    add(JsonPrimitive("minutes"))
                }
            },
        ),
        Tool(
            name = "calendar_recurring_create",
            description = "Создать повторяющееся событие календаря. title, dtstart (ISO-дата или epoch millis) и rrule (RFC5545, напр. FREQ=WEEKLY;COUNT=10) обязательны; укажи duration (напр. PT1H) или dtend. calendar_id необязателен. Действие изменяет данные календаря.",
            dangerous = true,
            needsPermission = "android.permission.WRITE_CALENDAR",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("title") {
                        put("type", "string")
                        put("description", "Название события")
                    }
                    putJsonObject("dtstart") {
                        put("type", "string")
                        put("description", "Начало события (ISO-дата или epoch millis)")
                    }
                    putJsonObject("duration") {
                        put("type", "string")
                        put("description", "Длительность в формате RFC5545 (напр. PT1H) или число секунд")
                    }
                    putJsonObject("dtend") {
                        put("type", "string")
                        put("description", "Конец события (ISO-дата или epoch millis) — альтернатива duration")
                    }
                    putJsonObject("rrule") {
                        put("type", "string")
                        put("description", "Правило повторения RFC5545, напр. FREQ=WEEKLY;COUNT=10")
                    }
                    putJsonObject("calendar_id") {
                        put("type", "string")
                        put("description", "Идентификатор календаря (id из calendar_list; необязательно)")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("title"))
                    add(JsonPrimitive("dtstart"))
                    add(JsonPrimitive("rrule"))
                }
            },
        ),
    )
}
