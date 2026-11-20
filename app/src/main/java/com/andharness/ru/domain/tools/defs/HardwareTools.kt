package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- hardware: IR / USB / VPN ----
internal object HardwareTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "ir_transmit",
            description = "Передать ИК-сигнал (если у устройства есть ИК-излучатель). Требуется разрешение TRANSMIT_IR. Если эмиттера нет, инструмент честно сообщит о недоступности. Допускаются только шаблоны короче 2 секунд.",
            needsPermission = "android.permission.TRANSMIT_IR",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("frequency") {
                        put("type", "string")
                        put("description", "Частота несущей в герцах, например 38000")
                    }
                    putJsonObject("pattern") {
                        put("type", "string")
                        put("description", "Чередующиеся длительности вкл/выкл в микросекундах через запятую, например 9000,4500,560,560")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("frequency"))
                    add(JsonPrimitive("pattern"))
                }
            },
        ),
        Tool(
            name = "usb_list",
            description = "Список подключённых USB-устройств (OTG): vendor/product id, класс, названия и наличие доступа. Разрешение не требуется.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "usb_request_permission",
            description = "Запросить доступ к USB-устройству: показывает системный диалог и сообщает его реальный результат (предоставлено/отклонено/нет ответа). Доступ к устройству действует до его отключения.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("vendor_id") {
                        put("type", "string")
                        put("description", "Идентификатор производителя (число, десятичное или 0x…), например 0x18d1")
                    }
                    putJsonObject("product_id") {
                        put("type", "string")
                        put("description", "Идентификатор продукта (число, десятичное или 0x…), например 0x4ee7")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("vendor_id"))
                    add(JsonPrimitive("product_id"))
                }
            },
        ),
        Tool(
            name = "usb_info",
            description = "Подробности USB-устройства: интерфейсы и эндпойнты. Требует уже выданного доступа (см. usb_request_permission). Устройство открывается только для чтения метаданных: интерфейс не захватывается и данные не передаются — для обмена нужен протокол конкретного устройства.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("vendor_id") {
                        put("type", "string")
                        put("description", "Идентификатор производителя (число, десятичное или 0x…)")
                    }
                    putJsonObject("product_id") {
                        put("type", "string")
                        put("description", "Идентификатор продукта (число, десятичное или 0x…)")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("vendor_id"))
                    add(JsonPrimitive("product_id"))
                }
            },
        ),
        Tool(
            name = "vpn_status",
            description = "Активен ли VPN и какой тип сети используется: наличие VPN-транспорта в активной сети и транспорт сети по умолчанию. Только чтение, разрешение не требуется.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "vpn_prepare",
            description = "Запросить согласие пользователя на VPN (VpnService.prepare): открывает системный диалог. Если согласие уже получено, prepare возвращает null — инструмент сообщит об этом. Инструмент не утверждает, что пользователь согласился. Полноценный VPN-туннель не создаётся (нужен удалённый сервер), поэтому vpn_connect отсутствует.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "sim_slots_list",
            description = "Список SIM-слотов (подписок): индекс слота, subscriptionId, оператор, " +
                "отображаемое имя, тип (физическая/eSIM) и признак «по умолчанию» для звонков, SMS и " +
                "данных. Только чтение. Требует READ_PHONE_STATE; без него или без SIM честно " +
                "сообщает об этом и не выдумывает данные.",
            needsPermission = "android.permission.READ_PHONE_STATE",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "sim_numbers",
            description = "Номера телефонов активных SIM-подписок: слот, оператор, ICCID (если " +
                "доступен) и номер из SubscriptionManager.getPhoneNumber(subId) (API 33+) / " +
                "TelephonyManager.getLine1Number(). Только чтение. Требует READ_PHONE_STATE; для " +
                "самого номера нужно READ_PHONE_NUMBERS. Если оператор/SIM не отдают MSISDN, честно " +
                "возвращает «не определено» по каждой подписке и НИКОГДА не выдумывает номер.",
            needsPermission = "android.permission.READ_PHONE_STATE",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "sim_set_default_sms",
            description = "Сделать указанную SIM (subscription_id) подпиской по умолчанию для SMS. " +
                "Обычному приложению это недоступно: публичного API нет, требуется системная " +
                "привилегия MODIFY_PHONE_STATE. Инструмент НИКОГДА не сообщает об успехе: он " +
                "проверяет id и честно объясняет, почему изменение невозможно, и что роль " +
                "SMS-приложения по умолчанию такого права не даёт.",
            needsPermission = "android.permission.READ_PHONE_STATE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("subscription_id") {
                        put("type", "string")
                        put("description", "Идентификатор SIM-подписки (id из sim_slots_list)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("subscription_id")) }
            },
        ),
        Tool(
            name = "esim_status",
            description = "Состояние eSIM: доступность встроенной подписки (EuiccManager.isEnabled) и " +
                "информация о чипе. EID и данные eUICC возвращаются только при наличии " +
                "привилегий оператора; иначе инструмент честно сообщает, что нужны carrier " +
                "privileges, и НИКОГДА не выдумывает EID.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "battery_detail",
            description = "Подробно о батарее: заряд, температура, напряжение, состояние, здоровье, источник питания.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "thermal_status",
            description = "Текущий термальный статус устройства.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "battery_prediction",
            description = "Прогноз оставшегося времени работы от батареи.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "power_saver_get",
            description = "Включён ли режим энергосбережения.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "device_idle_get",
            description = "В Doze/Idle ли устройство.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "battery_optimization_status",
            description = "Исключено ли приложение из оптимизации батареи.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "battery_optimization_request",
            description = "Открыть системный запрос на исключение из оптимизации батареи.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "screen_state_get",
            description = "Включён ли экран / интерактивно ли устройство.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "alarm_set",
            description = "Установить будильник на указанное время (hour 0-23, minute 0-59) с необязательным сообщением.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("hour") { put("type", "string"); put("description", "Часы 0-23") }
                    putJsonObject("minute") { put("type", "string"); put("description", "Минуты 0-59") }
                    putJsonObject("message") { put("type", "string"); put("description", "Подпись будильника (опционально)") }
                }
                putJsonArray("required") { add(JsonPrimitive("hour")); add(JsonPrimitive("minute")) }
            },
        ),
        Tool(
            name = "timer_set",
            description = "Установить таймер на указанное число секунд с необязательным сообщением.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("seconds") { put("type", "string"); put("description", "Длительность в секундах") }
                    putJsonObject("message") { put("type", "string"); put("description", "Подпись таймера (опционально)") }
                }
                putJsonArray("required") { add(JsonPrimitive("seconds")) }
            },
        ),
        Tool(
            name = "text_to_speech",
            description = "Озвучить текст вслух через системный синтез речи (TTS).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") { put("type", "string"); put("description", "Что произнести") }
                }
                putJsonArray("required") { add(JsonPrimitive("text")) }
            },
        ),
        Tool(
            name = "font_scale_set",
            description = "Изменить масштаб системного шрифта (например 1.0 — обычный, 1.3 — крупнее). Требует WRITE_SETTINGS.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("scale") { put("type", "string"); put("description", "Масштаб шрифта 0.5–2.0") }
                }
                putJsonArray("required") { add(JsonPrimitive("scale")) }
            },
        ),
        Tool(
            name = "flashlight_set",
            description = "Включить или выключить фонарик (вспышку камеры).",
            needsPermission = "android.permission.CAMERA",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("on") {
                        put("type", "boolean")
                        put("description", "Включить (true) или выключить (false) фонарик")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("on")) }
            },
        ),
        Tool(
            name = "storage_volumes",
            description = "Показать тома хранилища (внутренний, SD-карта, USB) с их состоянием и корневыми путями.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
    )
}
