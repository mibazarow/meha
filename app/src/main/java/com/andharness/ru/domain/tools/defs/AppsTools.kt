package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- apps ----
internal object AppsTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "app_list",
            description = "Список установленных приложений (package | название). Ограничено ~100 записей. Полезно, чтобы узнать package перед app_launch.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "app_launch",
            description = "Запустить приложение по имени пакета (package).",
            dangerous = true,
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("package") {
                        put("type", "string")
                        put("description", "Имя пакета запускаемого приложения")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("package")) }
            },
        ),
        Tool(
            name = "app_uninstall",
            description = "Запрос удаления приложения по имени пакета (package). Показывает системный диалог подтверждения.",
            dangerous = true,
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("package") {
                        put("type", "string")
                        put("description", "Имя пакета удаляемого приложения")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("package")) }
            },
        ),
        Tool(
            name = "app_package_info",
            description = "Сведения о приложении: версия, время установки и обновления, targetSdk, системное ли, установщик. Если приложение не найдено или скрыто ограничениями видимости пакетов — честно сообщает об этом. Остановить, очистить кэш или включить/выключить чужое приложение без системных прав нельзя.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("package") {
                        put("type", "string")
                        put("description", "Пакет приложения, напр. com.android.chrome")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("package")) }
            },
        ),
        Tool(
            name = "app_is_installed",
            description = "Проверить, установлено ли приложение по имени пакета (package). Если установлено — сообщает название, версию (versionName/versionCode) и включено ли оно. Если скрыто ограничениями видимости пакетов — честно сообщает об этом.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("package") {
                        put("type", "string")
                        put("description", "Пакет приложения, напр. com.android.chrome")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("package")) }
            },
        ),
        Tool(
            name = "app_permissions",
            description = "Разрешения, которые запрашивает приложение, и какие из них выданы.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("package") {
                        put("type", "string")
                        put("description", "Пакет приложения, напр. com.android.chrome")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("package")) }
            },
        ),
        Tool(
            name = "app_activities",
            description = "Список активностей, сервисов и ресиверов приложения (по имени пакета).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("package") {
                        put("type", "string")
                        put("description", "Пакет приложения, напр. com.android.chrome")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("package")) }
            },
        ),
        Tool(
            name = "app_services",
            description = "Список объявленных сервисов приложения (по имени пакета): имя, экспортируемость и, если задан, процесс. Только чтение через PackageManager; если приложение скрыто ограничениями видимости пакетов — честно сообщает об этом.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("package") {
                        put("type", "string")
                        put("description", "Пакет приложения, напр. com.android.chrome")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("package")) }
            },
        ),
        Tool(
            name = "app_receivers",
            description = "Список объявленных широковещательных приёмников (ресиверов) приложения (по имени пакета): имя, экспортируемость и, если задан, процесс. Только чтение через PackageManager; если приложение скрыто ограничениями видимости пакетов — честно сообщает об этом.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("package") {
                        put("type", "string")
                        put("description", "Пакет приложения, напр. com.android.chrome")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("package")) }
            },
        ),
        Tool(
            name = "role_status",
            description = "Проверить статус системной роли: доступна ли роль на устройстве и выдана ли она (и каким приложениям).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("role") {
                        put("type", "string")
                        put("description", "Роль: короткое имя (SMS, DIALER, BROWSER, HOME) или полное (android.app.role.SMS)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("role")) }
            },
        ),
        Tool(
            name = "role_request",
            description = "Попросить пользователя сделать приложение системной ролью (SMS, DIALER, BROWSER, HOME, CALL_SCREENING и т.п.). Показывает системный диалог и сообщает настоящий результат: выдана, отклонена или отменена. Нужна, например, чтобы sms_read_state мог менять статус прочтения.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("role") {
                        put("type", "string")
                        put("description", "Роль: короткое имя (SMS, DIALER, BROWSER, HOME, CALL_SCREENING) или полное (android.app.role.SMS)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("role")) }
            },
        ),
        Tool(
            name = "role_hold",
            description = "Проверить, держит ли приложение указанную системную роль (SMS, DIALER, BROWSER, HOME и т.п.). Помогает заранее понять, сработают ли зависящие от роли инструменты.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("role") {
                        put("type", "string")
                        put("description", "Роль: короткое имя (SMS, DIALER, BROWSER, HOME) или полное (android.app.role.SMS)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("role")) }
            },
        ),
        Tool(
            name = "call_screening_role_request",
            description = "Запросить роль скрининга звонков (ROLE_CALL_SCREENING) системным диалогом и сообщить настоящий результат. Учитывайте: назначенный CallScreeningService обязан ответить на входящий вызов в течение 5 секунд.",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "dialer_role_request",
            description = "Запросить роль приложения-телефона по умолчанию (ROLE_DIALER) системным диалогом и сообщить настоящий результат. Роль подразумевает InCallService с экраном вызова.",
            parameters = buildJsonObject { put("type", "object") },
        ),
    )
}
