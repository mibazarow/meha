package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- system ----
internal object SystemTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "brightness_set",
            description = "Установить яркость экрана в процентах (0–100). Требует разрешения WRITE_SETTINGS (запрашивается вручную).",
            needsPermission = "android.permission.WRITE_SETTINGS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("level") {
                        put("type", "string")
                        put("description", "Яркость экрана в процентах (0–100)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("level")) }
            },
        ),
        Tool(
            name = "volume_set",
            description = "Установить уровень громкости для типа звука (music, ring, notification, alarm). Уровень — 0–15.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("type") {
                        put("type", "string")
                        put("description", "Тип звука (music, ring, notification, alarm)")
                    }
                    putJsonObject("level") {
                        put("type", "string")
                        put("description", "Уровень громкости (0–15)")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("type"))
                    add(JsonPrimitive("level"))
                }
            },
        ),
        Tool(
            name = "wifi_set",
            description = "Запрос на включение/выключение Wi-Fi (on=true / on=false). На Android 10+ система запрещает обычным приложениям менять Wi-Fi напрямую: инструмент честно вернёт отказ, объяснит почему и откроет панель Wi-Fi в настройках. На старых версиях Android (до 10) Wi-Fi переключается реально.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("on") {
                        put("type", "boolean")
                        put("description", "Включить (true) или выключить (false) Wi-Fi")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("on")) }
            },
        ),
        Tool(
            name = "bluetooth_set",
            description = "Запрос на включение/выключение Bluetooth (on=true / on=false). На Android 12+ система запрещает обычным приложениям менять Bluetooth напрямую: инструмент честно вернёт отказ, объяснит почему и откроет панель Bluetooth в настройках. На старых версиях Android (до 12) Bluetooth переключается реально.",
            needsPermission = "android.permission.BLUETOOTH_CONNECT",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("on") {
                        put("type", "boolean")
                        put("description", "Включить (true) или выключить (false) Bluetooth")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("on")) }
            },
        ),
        Tool(
            name = "volume_get",
            description = "Получить текущую громкость для типа звука (music, ring, notification, alarm): текущее значение и максимум.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("type") {
                        put("type", "string")
                        put("description", "Тип звука (music, ring, notification, alarm)")
                    }
                }
            },
        ),
        Tool(
            name = "brightness_get",
            description = "Получить текущую яркость экрана в процентах (0–100).",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "ringer_set",
            description = "Установить режим звука телефона: normal (обычный), vibrate (вибрация), silent (без звука).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("mode") {
                        put("type", "string")
                        put("description", "Режим звука: normal | vibrate | silent")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("mode")) }
            },
        ),
        Tool(
            name = "rotation_set",
            description = "Включить или выключить авто-поворот экрана (on=true — авто, on=false — зафиксировать ориентацию). Требует WRITE_SETTINGS.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("on") {
                        put("type", "boolean")
                        put("description", "true — разрешить авто-поворот, false — зафиксировать")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("on")) }
            },
        ),
        Tool(
            name = "screen_timeout_set",
            description = "Установить время до выключения экрана в секундах. Требует WRITE_SETTINGS.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("seconds") {
                        put("type", "string")
                        put("description", "Таймаут экрана в секундах (целое число > 0)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("seconds")) }
            },
        ),
        Tool(
            name = "vibrate",
            description = "Включить вибрацию устройства на указанное время в миллисекундах (по умолчанию 500).",
            needsPermission = "android.permission.VIBRATE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("duration_ms") {
                        put("type", "string")
                        put("description", "Длительность вибрации в миллисекундах (1–10000)")
                    }
                }
            },
        ),
        Tool(
            name = "network_info",
            description = "Получить информацию о текущем сетевом подключении: тип (Wi-Fi/мобильная сеть/Ethernet/VPN), наличие интернета, IP-адрес.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "wifi_info",
            description = "Получить сведения о текущей Wi-Fi сети: SSID и IP-адрес (SSID может требовать разрешение на геолокацию).",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "bluetooth_devices",
            description = "Список сопряжённых (paired) Bluetooth-устройств: имя и MAC-адрес. Требует разрешения BLUETOOTH_CONNECT.",
            needsPermission = "android.permission.BLUETOOTH_CONNECT",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "ble_scan",
            description = "Сканировать Bluetooth Low Energy (BLE) устройства в течение duration_sec секунд и вернуть найденные устройства с адресом и уровнем сигнала, отсортированные по RSSI. Требует разрешения BLUETOOTH_SCAN и включённого Bluetooth. Сканирование без фильтра приостанавливается, когда экран выключен.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("duration_sec") {
                        put("type", "string")
                        put("description", "Длительность сканирования в секундах (1–30, по умолчанию 8)")
                    }
                    putJsonObject("name_filter") {
                        put("type", "string")
                        put("description", "Подстрока имени устройства для фильтра результатов (необязательно)")
                    }
                }
            },
        ),
        Tool(
            name = "ble_connect",
            description = "Подключиться к BLE-устройству по MAC-адресу и показать его GATT-сервисы и характеристики. Требует разрешения BLUETOOTH_CONNECT и включённого Bluetooth. Соединение закрывается после чтения списка сервисов.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("address") {
                        put("type", "string")
                        put("description", "MAC-адрес BLE-устройства, например AA:BB:CC:DD:EE:FF")
                    }
                    putJsonObject("duration_sec") {
                        put("type", "string")
                        put("description", "Таймаут подключения и обнаружения сервисов в секундах (1–30, по умолчанию 10)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("address")) }
            },
        ),
        Tool(
            name = "dns_lookup",
            description = "Разрешить доменное имя в IP-адреса (DNS-запрос).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("host") { put("type", "string"); put("description", "Доменное имя, например example.com") }
                }
                putJsonArray("required") { add(JsonPrimitive("host")) }
            },
        ),
        Tool(
            name = "ping",
            description = "Проверить доступность хоста через ping (по умолчанию 4 пакета).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("host") { put("type", "string"); put("description", "Хост или IP") }
                    putJsonObject("count") { put("type", "string"); put("description", "Число пакетов 1–10 (по умолчанию 4)") }
                }
                putJsonArray("required") { add(JsonPrimitive("host")) }
            },
        ),
        Tool(
            name = "wifi_scan",
            description = "Сканировать доступные Wi-Fi сети (SSID, уровень сигнала). Может требовать разрешение на геолокацию.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "network_detail",
            description = "Подробности активной сети: тип транспорта, metered, validated, доступность интернета.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "nsd_discover",
            description = "Обнаружить сервисы в локальной сети (mDNS/NSD) за указанное время.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("service_type") {
                        put("type", "string")
                        put("description", "Тип сервиса, например _http._tcp. (по умолчанию _http._tcp.)")
                    }
                    putJsonObject("timeout_sec") {
                        put("type", "string")
                        put("description", "Сколько секунд искать, 1–30 (по умолчанию 5)")
                    }
                }
            },
        ),
        Tool(
            name = "telephony_info",
            description = "Базовая информация о телефонии: оператор, тип сети, роуминг, SIM.",
            needsPermission = "android.permission.READ_PHONE_STATE",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
    )
}
