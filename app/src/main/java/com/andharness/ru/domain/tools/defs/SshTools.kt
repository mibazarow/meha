package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- update.md §5.3: SSH registry + exec + SFTP ----
internal object SshTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "ssh_server_add",
            description = "Зарегистрировать SSH-сервер. name — понятное уникальное имя (например " +
                "«vps-prod»), host, port (по умолчанию 22), user, auth_type — PASSWORD или KEY. " +
                "Для PASSWORD передай password; для KEY передай key_ref — SAF-URI приватного ключа " +
                "(доступ к документу уже должен быть выдан) и, если ключ защищён, passphrase. " +
                "Секреты НЕ попадают в базу: пароль и парольная фраза сохраняются в " +
                "EncryptedSharedPreferences на базе Android Keystore, а приватный ключ не " +
                "копируется — читается из файла по key_ref при подключении. Имя уникально: если " +
                "такое имя уже есть, инструмент откажет, а не перезапишет молча.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Уникальное понятное имя сервера, например vps-prod.")
                    }
                    putJsonObject("host") {
                        put("type", "string")
                        put("description", "Адрес или имя хоста, например example.com или 10.0.0.5.")
                    }
                    putJsonObject("port") {
                        put("type", "string")
                        put("description", "Порт SSH, по умолчанию 22.")
                    }
                    putJsonObject("user") {
                        put("type", "string")
                        put("description", "Имя пользователя для входа.")
                    }
                    putJsonObject("auth_type") {
                        put("type", "string")
                        put("description", "PASSWORD (пароль) или KEY (приватный ключ).")
                    }
                    putJsonObject("password") {
                        put("type", "string")
                        put("description", "Пароль для auth_type=PASSWORD. Не сохраняется в базе.")
                    }
                    putJsonObject("key_ref") {
                        put("type", "string")
                        put("description", "SAF-URI приватного ключа для auth_type=KEY, например " +
                            "content://com.android.providers.downloads.documents/document/…")
                    }
                    putJsonObject("passphrase") {
                        put("type", "string")
                        put("description", "Парольная фраза приватного ключа, если он защищён.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("name"))
                    add(JsonPrimitive("host"))
                    add(JsonPrimitive("user"))
                    add(JsonPrimitive("auth_type"))
                }
            },
        ),
        Tool(
            name = "ssh_server_list",
            description = "Список зарегистрированных SSH-серверов: номер, имя, user@host:port и тип " +
                "аутентификации. Секреты (пароли, парольные фразы, ключи) никогда не показываются и " +
                "в базе не хранятся.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { }
            },
        ),
        Tool(
            name = "ssh_server_remove",
            description = "Удалить SSH-сервер из реестра по server (имя) или server_id. Вместе с " +
                "записью удаляются сохранённый пароль/парольная фраза и запись в известных ключах " +
                "хоста. Действие необратимо. Если сервер назван неоднозначно или не назван, " +
                "инструмент вернёт нумерованный список и спросит — ничего не удаляя.",
            dangerous = true,
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("server") {
                        put("type", "string")
                        put("description", "Имя сервера (ssh_server_list).")
                    }
                    putJsonObject("server_id") {
                        put("type", "string")
                        put("description", "Числовой id сервера (приоритетнее имени).")
                    }
                }
            },
        ),
        Tool(
            name = "ssh_server_rename",
            description = "Переименовать сохранённый SSH-сервер. server — имя (или id) сервера из " +
                "ssh_server_list, name — новое понятное имя. Имя уникально: если такое имя уже " +
                "занято другим сервером, инструмент откажет, а не перезапишет. Адрес, пользователь, " +
                "пароль/ключ и запись known_hosts не затрагиваются. Действие безопасное.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("server") {
                        put("type", "string")
                        put("description", "Имя сервера (ssh_server_list).")
                    }
                    putJsonObject("server_id") {
                        put("type", "string")
                        put("description", "Числовой id сервера (приоритетнее имени).")
                    }
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Новое понятное уникальное имя сервера, например vps-prod.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("server"))
                    add(JsonPrimitive("name"))
                }
            },
        ),
        Tool(
            name = "ssh_server_update",
            description = "Изменить сохранённые поля SSH-сервера (server — имя или id, либо server_id). " +
                "Можно передать любые из host, port, user, secret; изменяются только переданные поля, " +
                "остальные сохраняются. secret — новый пароль для auth_type=PASSWORD или парольная " +
                "фраза приватного ключа для KEY; он сохраняется в EncryptedSharedPreferences, а не в " +
                "базе, и не показывается. Смена host/port делает ключ хоста неизвестным: при первом " +
                "подключении потребуется trust_host=true.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("server") {
                        put("type", "string")
                        put("description", "Имя сервера (ssh_server_list).")
                    }
                    putJsonObject("server_id") {
                        put("type", "string")
                        put("description", "Числовой id сервера (приоритетнее имени).")
                    }
                    putJsonObject("host") {
                        put("type", "string")
                        put("description", "Новый адрес или имя хоста. Не обязательно.")
                    }
                    putJsonObject("port") {
                        put("type", "string")
                        put("description", "Новый порт SSH (1–65535). Не обязательно.")
                    }
                    putJsonObject("user") {
                        put("type", "string")
                        put("description", "Новое имя пользователя для входа. Не обязательно.")
                    }
                    putJsonObject("secret") {
                        put("type", "string")
                        put("description", "Новый пароль (PASSWORD) или парольная фраза ключа (KEY). " +
                            "Не сохраняется в базе. Не обязательно.")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("server")) }
            },
        ),
        Tool(
            name = "ssh_exec",
            description = "Выполнить команду на SSH-сервере (server или server_id) и вернуть stdout, " +
                "stderr и код возврата. timeout_sec ограничивает выполнение (по умолчанию 60, " +
                "максимум 300): по таймауту команда прерывается, а не висит. " +
                "Устройство КЛЮЧА ХОСТА: при первом подключении ключ неизвестен — подключение " +
                "отклоняется с показом отпечатка; после сверки повторите с trust_host=true, чтобы " +
                "запомнить ключ. Если ключ изменился, подключение отклоняется всегда. " +
                "Деструктивные команды (rm -rf, dd, mkfs, reboot, shutdown, systemctl stop, " +
                "рекурсивные chmod/chown, перенаправление на /dev/…, fork-бомба и т.п.) " +
                "выполняются немедленно: подтверждения пользователя нет, команда лишь помечается " +
                "предупреждением в результате. Интерактивный ввод не поддерживается: sudo, " +
                "спрашивающий пароль, не сработает.",
            dangerous = true,
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("server") {
                        put("type", "string")
                        put("description", "Имя сервера (ssh_server_list).")
                    }
                    putJsonObject("server_id") {
                        put("type", "string")
                        put("description", "Числовой id сервера (приоритетнее имени).")
                    }
                    putJsonObject("command") {
                        put("type", "string")
                        put("description", "Команда оболочки для выполнения на сервере.")
                    }
                    putJsonObject("timeout_sec") {
                        put("type", "string")
                        put("description", "Таймаут, с (по умолчанию 60, макс. 300).")
                    }
                    putJsonObject("trust_host") {
                        put("type", "boolean")
                        put("description", "true — запомнить ключ хоста, если он ещё не подтверждён " +
                            "(после сверки отпечатка). Изменившийся ключ отклоняется всегда.")
                    }
                    putJsonObject("confirm_dangerous") {
                        put("type", "boolean")
                        put("description", "Устаревший параметр: принимается для совместимости, но " +
                            "больше ни на что не влияет — деструктивные команды выполняются без " +
                            "подтверждения.")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("command")) }
            },
        ),
        Tool(
            name = "ssh_sftp_list",
            description = "Листинг каталога на сервере по SFTP: server или server_id, path (по " +
                "умолчанию домашний каталог), limit (по умолчанию 200, максимум 1000). Для " +
                "неизвестного ключа хоста нужно trust_host=true после сверки отпечатка.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("server") {
                        put("type", "string")
                        put("description", "Имя сервера (ssh_server_list).")
                    }
                    putJsonObject("server_id") {
                        put("type", "string")
                        put("description", "Числовой id сервера (приоритетнее имени).")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к каталогу на сервере (по умолчанию домашний).")
                    }
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько записей показать (по умолчанию 200, максимум 1000).")
                    }
                    putJsonObject("trust_host") {
                        put("type", "boolean")
                        put("description", "Запомнить ключ хоста, если ещё не подтверждён.")
                    }
                    putJsonObject("timeout_sec") {
                        put("type", "string")
                        put("description", "Таймаут, с (по умолчанию 60, макс. 300).")
                    }
                }
            },
        ),
        Tool(
            name = "ssh_sftp_get",
            description = "Скачать файл с сервера по SFTP: server или server_id, remote — путь на " +
                "сервере, local — путь сохранения относительно корня файловых инструментов. Файл " +
                "пишется атомарно (временный «.part» + переименование), поэтому неполный файл не " +
                "остаётся под конечным именем. Размер ограничен 128 МБ. Для большего используйте " +
                "ssh_exec с curl/base64 или качайте частями.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("server") {
                        put("type", "string")
                        put("description", "Имя сервера (ssh_server_list).")
                    }
                    putJsonObject("server_id") {
                        put("type", "string")
                        put("description", "Числовой id сервера (приоритетнее имени).")
                    }
                    putJsonObject("remote") {
                        put("type", "string")
                        put("description", "Путь к файлу на сервере.")
                    }
                    putJsonObject("local") {
                        put("type", "string")
                        put("description", "Куда сохранить (в общем хранилище).")
                    }
                    putJsonObject("trust_host") {
                        put("type", "boolean")
                        put("description", "Запомнить ключ хоста, если ещё не подтверждён.")
                    }
                    putJsonObject("timeout_sec") {
                        put("type", "string")
                        put("description", "Таймаут, с (по умолчанию 60, макс. 300).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("remote"))
                    add(JsonPrimitive("local"))
                }
            },
        ),
        Tool(
            name = "ssh_sftp_put",
            description = "Выгрузить локальный файл на сервер по SFTP: server или server_id, local — " +
                "путь относительно корня файловых инструментов, remote — путь на сервере. По " +
                "умолчанию перезапись ЗАПРЕЩЕНА: если файл на сервере уже есть, инструмент " +
                "откажет — передайте overwrite=true, чтобы перезаписать. Размер ограничен 128 МБ.",
            dangerous = true,
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("server") {
                        put("type", "string")
                        put("description", "Имя сервера (ssh_server_list).")
                    }
                    putJsonObject("server_id") {
                        put("type", "string")
                        put("description", "Числовой id сервера (приоритетнее имени).")
                    }
                    putJsonObject("local") {
                        put("type", "string")
                        put("description", "Путь к локальному файлу относительно корня файловых инструментов.")
                    }
                    putJsonObject("remote") {
                        put("type", "string")
                        put("description", "Путь назначения на сервере.")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "true — разрешить перезапись существующего файла на сервере.")
                    }
                    putJsonObject("trust_host") {
                        put("type", "boolean")
                        put("description", "Запомнить ключ хоста, если ещё не подтверждён.")
                    }
                    putJsonObject("timeout_sec") {
                        put("type", "string")
                        put("description", "Таймаут, с (по умолчанию 60, макс. 300).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("local"))
                    add(JsonPrimitive("remote"))
                }
            },
        ),
    )
}
