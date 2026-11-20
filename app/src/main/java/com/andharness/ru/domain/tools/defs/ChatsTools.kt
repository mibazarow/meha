package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- chats: on-demand cross-chat read (privacy-preserving, tool output only) ----
internal object ChatsTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "chat_list",
            description = "Показать недавние чаты с идентификатором, названием, временем обновления, " +
                "числом сообщений и коротким фрагментом последнего сообщения. Передай limit — сколько " +
                "чатов вернуть (по умолчанию 20, максимум 50). Только чтение: ничего не меняет и не " +
                "подставляет содержимое других чатов в текущий ответ. Вызывай, только когда " +
                "пользователь явно просит показать или найти его чаты.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько чатов вернуть, 1–50 (по умолчанию 20)")
                    }
                }
            },
        ),
        Tool(
            name = "chat_search",
            description = "Найти ЧАТЫ Мехи и их сообщения по текстовому запросу (это чаты внутри " +
                "приложения, а не SMS/мессенджеры — для личных сообщений используй message_search). " +
                "Ищет совпадения в названиях чатов и в тексте сообщений, возвращает идентификатор и " +
                "название чата и короткие фрагменты совпадений. Передай query — текст для поиска, при " +
                "желании limit — сколько чатов вернуть (по умолчанию 20, максимум 50). Только чтение, " +
                "приватность сохраняется: " +
                "результат остаётся ответом инструмента и не подставляется в текущий чат автоматически. " +
                "Вызывай только по явной просьбе пользователя; если совпадений нет, честно сообщает об этом.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Текст для поиска по названиям чатов и сообщениям")
                    }
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько чатов вернуть, 1–50 (по умолчанию 20)")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("query"))
                }
            },
        ),
        Tool(
            name = "chat_recall",
            description = "Кратко пересказать (резюмировать) другую переписку с помощью настроенной " +
                "языковой модели: chat_id — идентификатор чата (по умолчанию самый свежий), limit — " +
                "сколько последних сообщений учесть (по умолчанию 30, максимум 100). Возвращает " +
                "короткое резюме на русском языке (ключевые пункты, решения, открытые вопросы). " +
                "Только чтение: содержимое чата не меняется. Честно сообщает, если чат не найден, пуст " +
                "или модель не настроена, и НИКОГДА не выдумывает содержание переписки.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("chat_id") {
                        put("type", "string")
                        put("description", "Чат (id; по умолчанию — самый свежий).")
                    }
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько последних сообщений учесть, 1–100 (по умолчанию 30)")
                    }
                }
            },
        ),
        Tool(
            name = "chat_create",
            description = "Создать новый чат и вернуть его идентификатор и название. Передай title — название " +
                "чата (необязательно; по умолчанию «Новый чат»). Вызывай, только если пользователь явно " +
                "просит создать новый чат.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("title") {
                        put("type", "string")
                        put("description", "Название нового чата (необязательно; по умолчанию «Новый чат»)")
                    }
                }
            },
        ),
        Tool(
            name = "chat_rename",
            description = "Переименовать чат: title — новое название, chat_id — идентификатор чата " +
                "(можно НЕ указывать: тогда по умолчанию текущий чат, в котором ты говоришь). Меняет " +
                "только название чата; сообщения не затрагиваются. Честно сообщает, если чат не найден.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("chat_id") {
                        put("type", "string")
                        put("description", "Чат (id). Необязательно: по умолчанию текущий чат")
                    }
                    putJsonObject("title") {
                        put("type", "string")
                        put("description", "Новое название чата")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("title"))
                }
            },
        ),
        Tool(
            name = "chat_delete",
            description = "Удалить чат вместе со всеми его сообщениями (каскадно). chat_id — идентификатор " +
                "чата (можно НЕ указывать: тогда по умолчанию текущий чат), confirm — подтверждение " +
                "(должно быть true). Действие необратимо: чат и все его сообщения удаляются безвозвратно, " +
                "восстановить их нельзя. Честно сообщает, если чат не найден.",
            dangerous = true,
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("chat_id") {
                        put("type", "string")
                        put("description", "Чат (id). Необязательно: по умолчанию текущий чат")
                    }
                    putJsonObject("confirm") {
                        put("type", "boolean")
                        put("description", "Подтверждение удаления: должно быть true, иначе чат не удаляется")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("confirm"))
                }
            },
        ),
        Tool(
            name = "chat_pin",
            description = "Закрепить или открепить чат в списке: chat_id — идентификатор чата (можно НЕ " +
                "указывать: тогда по умолчанию текущий чат), pinned — true чтобы закрепить (по умолчанию " +
                "true), false чтобы открепить. Закреплённые чаты показываются сверху; порядок для " +
                "chat_recall не меняется. Честно сообщает, если чат не найден.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("chat_id") {
                        put("type", "string")
                        put("description", "Чат (id). Необязательно: по умолчанию текущий чат")
                    }
                    putJsonObject("pinned") {
                        put("type", "boolean")
                        put("description", "true — закрепить (по умолчанию), false — открепить")
                    }
                }
            },
        ),
        Tool(
            name = "chat_clear_messages",
            description = "Очистить сообщения чата, оставив сам чат (название и сам чат сохраняются). chat_id — " +
                "идентификатор чата (можно НЕ указывать: тогда по умолчанию текущий чат), confirm — " +
                "подтверждение (должно быть true). Действие необратимо: сообщения удаляются безвозвратно " +
                "и восстановить их нельзя. Честно сообщает, если чат не найден.",
            dangerous = true,
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("chat_id") {
                        put("type", "string")
                        put("description", "Чат (id). Необязательно: по умолчанию текущий чат")
                    }
                    putJsonObject("confirm") {
                        put("type", "boolean")
                        put("description", "Подтверждение очистки: должно быть true, иначе сообщения не удаляются")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("confirm"))
                }
            },
        ),
        Tool(
            name = "chat_export",
            description = "Экспортировать переписку чата в файл. chat_id — идентификатор чата (можно НЕ " +
                "указывать: тогда по умолчанию текущий чат), format — md, txt или json (по умолчанию md), " +
                "path — необязательный абсолютный путь к файлу (по умолчанию " +
                "<filesDir>/exports/chat-<id>-<время>.<расширение>). Возвращает реальный путь к файлу и число " +
                "сообщений. Только чтение данных чата. Честно сообщает, если чат не найден или файл не записался.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("chat_id") {
                        put("type", "string")
                        put("description", "Чат (id). Необязательно: по умолчанию текущий чат")
                    }
                    putJsonObject("format") {
                        put("type", "string")
                        put("description", "Формат экспорта: md, txt или json (по умолчанию md)")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Абсолютный путь к файлу экспорта (необязательно)")
                    }
                }
            },
        ),
        Tool(
            name = "device_info",
            description = "Получить информацию об устройстве: модель, производитель, Android, заряд батареи, объём памяти.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "device_facts_list",
            description = "Показать кэш фактов об устройстве: ключ, категория, когда обновлён, свежий ли, размер значения. Факты — это запомненные сведения о телефоне (модель, Android, приложения, тома, роли, сеть, спец-доступы и т.п.), которые агент переиспользует вместо повторного сканирования.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "device_facts_get",
            description = "Получить один факт об устройстве по ключу. Если факт в кэше свежий — вернёт его; если устарел — соберёт заново и честно скажет, что обновил. Ключи: device_model, android_version, screen, installed_apps, default_roles, home_launcher, storage_volumes, storage_space, current_network, accounts_count, contacts_count, sensors, special_access, locale_timezone, own_app_version.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("key") {
                        put("type", "string")
                        put("description", "Ключ факта, например installed_apps или device_model")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("key")) }
            },
        ),
        Tool(
            name = "device_facts_refresh",
            description = "Пересобрать факт(ы) об устройстве из реальных Android API и сохранить в кэш. Укажи key, чтобы обновить один факт, либо не указывай (или key=all), чтобы обновить все доступные факты.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("key") {
                        put("type", "string")
                        put("description", "Ключ факта (необязательно); пусто или all — обновить все факты")
                    }
                }
            },
        ),
        Tool(
            name = "device_facts_forget",
            description = "Удалить факт(ы) об устройстве из кэша и записать причину MANUAL в журнал. Укажи key, чтобы удалить один факт, либо не указывай (или key=all), чтобы очистить весь кэш фактов.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("key") {
                        put("type", "string")
                        put("description", "Ключ факта (необязательно); пусто или all — очистить весь кэш")
                    }
                }
            },
        ),
        Tool(
            name = "notification_post",
            description = "Отправить простое уведомление на устройство с указанными названием и текстом. Полезно, чтобы сообщить пользователю о результате задачи. Укажи chat_id, чтобы тап по уведомлению открывал нужный чат.",
            needsPermission = "android.permission.POST_NOTIFICATIONS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("title") {
                        put("type", "string")
                        put("description", "Название уведомления")
                    }
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "Текст уведомления")
                    }
                    putJsonObject("chat_id") {
                        put("type", "string")
                        put("description", "Идентификатор чата, который откроется при тапе по уведомлению (необязательно)")
                    }
                    putJsonObject("message_id") {
                        put("type", "string")
                        put("description", "Идентификатор сообщения для прокрутки к нему (необязательно)")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("title"))
                    add(JsonPrimitive("text"))
                }
            },
        ),
    )
}
