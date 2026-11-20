package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- update.md §1: system + network extras ----
internal object SystemNetworkExtrasTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "locale_app_set",
            description = "Сменить локаль приложения (per-app language, только Android 13 / API 33+). " +
                "lang — языковой тег, например ru или en-US; необязательный mode: system (вернуть системную " +
                "локаль) или default (установить lang). Инструмент читает результат обратно и честно сообщает, " +
                "что реально применила система; на API ниже 33 сообщает о недоступности.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("lang") {
                        put("type", "string")
                        put("description", "Языковой тег, например ru или en-US")
                    }
                    putJsonObject("mode") {
                        put("type", "string")
                        put("description", "system — вернуть системную локаль; default — установить lang (по умолчанию)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("lang")) }
            },
        ),
        Tool(
            name = "wifi_connect_specifier",
            description = "Подключиться к Wi-Fi сети по SSID и паролю без геолокации, через WifiNetworkSpecifier " +
                "(Android 10 / API 29+). Ждёт реального результата (сеть стала доступна / система не нашла сеть) " +
                "с ограничением timeout_sec; сетевой запрос освобождается после ответа.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("ssid") {
                        put("type", "string")
                        put("description", "Имя сети (SSID), например HomeWiFi")
                    }
                    putJsonObject("password") {
                        put("type", "string")
                        put("description", "Пароль WPA2 (необязательно; без него сеть считается открытой)")
                    }
                    putJsonObject("timeout_sec") {
                        put("type", "string")
                        put("description", "Таймаут ожидания в секундах, 5–120 (по умолчанию 30)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("ssid")) }
            },
        ),
        Tool(
            name = "wifi_suggest_add",
            description = "Предложить системе Wi-Fi сеть для автоподключения (WifiNetworkSuggestion, Android 10 / " +
                "API 29+). Решение о фактическом подключении принимает система, а не приложение: инструмент лишь " +
                "добавляет предложение и честно сообщает статус-код ответа системы.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("ssid") {
                        put("type", "string")
                        put("description", "Имя сети (SSID), например HomeWiFi")
                    }
                    putJsonObject("password") {
                        put("type", "string")
                        put("description", "Пароль WPA2 (необязательно; без него сеть считается открытой)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("ssid")) }
            },
        ),
        Tool(
            name = "wifi_direct_discover",
            description = "Найти пиры Wi-Fi Direct (P2P) за timeout_sec секунд. Возвращает имя, MAC-адрес и статус " +
                "каждого пира. Требует включённого Wi-Fi и разрешения (NEARBY_WIFI_DEVICES на Android 12+, иначе " +
                "геолокация); честно сообщает, если Wi-Fi Direct недоступен.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("timeout_sec") {
                        put("type", "string")
                        put("description", "Сколько секунд искать, 5–60 (по умолчанию 15)")
                    }
                }
            },
        ),
        Tool(
            name = "wifi_direct_connect",
            description = "Подключиться к пиру Wi-Fi Direct по MAC-адресу (device_address) и сообщить реальный " +
                "результат: сформирована ли группа, роль устройства и адрес владельца группы. Необязательный " +
                "wps_pin передаётся для WPS-подключения по PIN-коду.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("device_address") {
                        put("type", "string")
                        put("description", "MAC-адрес пира Wi-Fi Direct, например AA:BB:CC:DD:EE:FF")
                    }
                    putJsonObject("wps_pin") {
                        put("type", "string")
                        put("description", "WPS PIN-код (необязательно; без него используется PBC)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("device_address")) }
            },
        ),
        Tool(
            name = "wifi_direct_disconnect",
            description = "Разорвать Wi-Fi Direct: удалить текущую P2P-группу (removeGroup) и отменить ожидающее " +
                "подключение (cancelConnect). Честно сообщает результат каждой операции; если группы не было, " +
                "код ошибки — это норма.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "hotspot_local_start",
            description = "Поднять локальную точку доступа без интернета (startLocalOnlyHotspot, Android 12+ / " +
                "API 31+) и вернуть её SSID и пароль. Интернет клиентам не раздаётся. Точка работает, пока " +
                "приложение удерживает резервирование; остановить — через hotspot_local_stop.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "hotspot_local_stop",
            description = "Остановить ранее запущенную локальную точку доступа (закрыть резервирование " +
                "startLocalOnlyHotspot) и, где возможно, проверить реальное состояние точки доступа.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "wifi_rtt_range",
            description = "Оценить дистанцию до точки доступа по Wi-Fi RTT / 802.11mc (Android 9 / API 28+). " +
                "bssid необязателен (по умолчанию — текущая точка доступа). Возвращает дистанцию и погрешность, " +
                "честно сообщает, если железо или точка доступа не поддерживают RTT.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("bssid") {
                        put("type", "string")
                        put("description", "BSSID точки доступа, например AA:BB:CC:DD:EE:FF (по умолчанию текущая)")
                    }
                    putJsonObject("timeout_sec") {
                        put("type", "string")
                        put("description", "Таймаут измерения в секундах, 3–60 (по умолчанию 10)")
                    }
                }
            },
        ),
    )
}
