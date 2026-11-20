package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- update.md §1: приложения / уведомления / сеть (остаток) ----
internal object AppsNotificationsNetworkTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "app_ops_self",
            description = "Прочитать состояние AppOps (app-ops) для собственного приложения: разрешено, запрещено или «по умолчанию». Необязательный op — конкретная операция (например android:fine_location, android:camera, android:record_audio); если он не задан, выводится полезный набор. Читается только свой UID: состояние операций других пакетов обычному приложению недоступно.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("op") {
                        put("type", "string")
                        put("description", "Операция AppOps, например android:fine_location (необязательно; иначе набор по умолчанию)")
                    }
                }
                putJsonArray("required") { }
            },
        ),
        Tool(
            name = "shortcut_pin",
            description = "Предложить пользователю закрепить ярлык (shortcut) на домашнем экране. id — уникальный идентификатор ярлыка, label — подпись, intent_uri — необязательная ссылка, которую открывает ярлык. Показывает системный диалог лаунчера и честно сообщает, подтвердил ли пользователь закрепление; если лаунчер не поддерживает закрепление, сообщает об этом.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "Уникальный идентификатор ярлыка, например notes")
                    }
                    putJsonObject("label") {
                        put("type", "string")
                        put("description", "Подпись ярлыка на домашнем экране")
                    }
                    putJsonObject("intent_uri") {
                        put("type", "string")
                        put("description", "Необязательная ссылка (URI), которую открывает ярлык")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("id"))
                    add(JsonPrimitive("label"))
                }
            },
        ),
        Tool(
            name = "widget_pin",
            description = "Предложить пользователю закрепить виджет приложения на домашнем экране (AppWidgetManager.requestPinAppWidget). Показывает системный диалог; если у приложения нет собственных виджетов или лаунчер не поддерживает закрепление — честно сообщает об этом.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { }
            },
        ),
        Tool(
            name = "app_data_usage",
            description = "Показать сетевой трафик конкретного приложения (принято/отправлено/всего) за период. Требует спец-доступа «Доступ к данным об использовании» (PACKAGE_USAGE_STATS); без него честно сообщает, что доступ не выдан.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("package") {
                        put("type", "string")
                        put("description", "Имя пакета приложения, например com.example.app")
                    }
                    putJsonObject("days") {
                        put("type", "string")
                        put("description", "Число дней 1–90 (по умолчанию 7)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("package")) }
            },
        ),
        Tool(
            name = "network_data_usage",
            description = "Показать общий трафик устройства за период отдельно по Wi-Fi и мобильной сети. Требует спец-доступа «Доступ к данным об использовании» (PACKAGE_USAGE_STATS); без него честно сообщает, что доступ не выдан.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("days") {
                        put("type", "string")
                        put("description", "Число дней 1–90 (по умолчанию 7)")
                    }
                }
                putJsonArray("required") { }
            },
        ),
        Tool(
            name = "notification_extras",
            description = "Показать полный текст конкретного уведомления (заголовок, текст, большой текст, подзаголовок) и его действия, включая наличие поля быстрого ответа (RemoteInput). Укажите key из notification_list или package. Требует включённого доступа к уведомлениям; иначе честно сообщает об этом.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("key") {
                        put("type", "string")
                        put("description", "key уведомления из notification_list (необязательно, но нужен key или package)")
                    }
                    putJsonObject("package") {
                        put("type", "string")
                        put("description", "Имя пакета: берётся первое активное уведомление этого пакета (необязательно)")
                    }
                }
                putJsonArray("required") { }
            },
        ),
        Tool(
            name = "notification_snooze",
            description = "Отложить уведомление на указанное время (NotificationListenerService.snoozeNotification). Требует включённого доступа к уведомлениям; честно сообщает, если система не подтвердила отложение.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("key") {
                        put("type", "string")
                        put("description", "key уведомления из notification_list")
                    }
                    putJsonObject("minutes") {
                        put("type", "string")
                        put("description", "На сколько минут отложить, 1–1440 (по умолчанию 15)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("key")) }
            },
        ),
    )
}
