package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- coding bridge: git over the existing SSH stack ----
internal object GitBridgeTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "repo_status",
            description = "Статус git-репозитория на SSH-сервере (server или server_id): " +
                "dir — путь к каталогу репозитория на сервере. Выполняет `git -C <dir> status` и " +
                "честно возвращает текущую ветку и изменённые файлы вместе с кодом возврата. " +
                "short (по умолчанию true) добавляет --short --branch — компактно и с веткой; " +
                "short=false даёт полный вывод. Большой вывод обрезается с явной пометкой. Если " +
                "каталог не является git-репозиторием, git вернёт ошибку — инструмент сообщит её, " +
                "а не выдумает результат. Интерактивный ввод не поддерживается.",
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
                    putJsonObject("dir") {
                        put("type", "string")
                        put("description", "Путь к репозиторию на сервере (например ~/project).")
                    }
                    putJsonObject("short") {
                        put("type", "boolean")
                        put("description", "true (по умолчанию) — краткий статус с веткой " +
                            "(--short --branch); false — полный git status.")
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
                putJsonArray("required") { add(JsonPrimitive("dir")) }
            },
        ),
        Tool(
            name = "repo_diff",
            description = "Показать изменения в git-репозитории на SSH-сервере (server или " +
                "server_id): dir — путь к репозиторию, staged (по умолчанию false; true — " +
                "`git diff --staged`, то есть проиндексированные изменения) и необязательный path — " +
                "ограничить вывод одним файлом или каталогом (`-- <path>`). Вывод ограничен по " +
                "размеру с пометкой об обрезке, код возврата git показывается честно.",
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
                    putJsonObject("dir") {
                        put("type", "string")
                        put("description", "Путь к репозиторию на сервере (например ~/project).")
                    }
                    putJsonObject("staged") {
                        put("type", "boolean")
                        put("description", "true — показать проиндексированные изменения " +
                            "(--staged); по умолчанию показать изменения рабочего дерева.")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Необязательный путь внутри репозитория, чтобы ограничить " +
                            "diff одним файлом или каталогом.")
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
                putJsonArray("required") { add(JsonPrimitive("dir")) }
            },
        ),
        Tool(
            name = "repo_log",
            description = "История коммитов git-репозитория на SSH-сервере (server или server_id): " +
                "dir — путь к репозиторию, limit — сколько последних коммитов показать (по " +
                "умолчанию 20, максимум 100; большее значение ограничивается с явной пометкой). " +
                "Выполняет `git -C <dir> log --oneline -n <limit>`. Несуществующий каталог или " +
                "репозиторий без коммитов отражаются реальным кодом возврата и сообщением git.",
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
                    putJsonObject("dir") {
                        put("type", "string")
                        put("description", "Путь к репозиторию на сервере (например ~/project).")
                    }
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько последних коммитов показать (по умолчанию 20, " +
                            "максимум 100).")
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
                putJsonArray("required") { add(JsonPrimitive("dir")) }
            },
        ),
        Tool(
            name = "repo_apply_patch",
            description = "Применить локальный git-патч к репозиторию на SSH-сервере (server или " +
                "server_id): dir — путь к репозиторию, patchPath — путь к локальному файлу патча " +
                "относительно корня файловых инструментов. Патч выгружается во временный файл на " +
                "сервере, сначала выполняется `git apply --check`, и только если проверка прошла — " +
                "`git apply`. При checkOnly=true патч только проверяется и НЕ применяется. " +
                "Временный файл удаляется. Инструмент никогда не утверждает, что патч применён, " +
                "без кода возврата 0 от git. Изменяет файлы репозитория.",
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
                    putJsonObject("dir") {
                        put("type", "string")
                        put("description", "Путь к репозиторию на сервере (например ~/project).")
                    }
                    putJsonObject("patchPath") {
                        put("type", "string")
                        put("description", "Путь к локальному файлу патча относительно корня файловых " +
                            "инструментов, например Downloads/fix.patch.")
                    }
                    putJsonObject("checkOnly") {
                        put("type", "boolean")
                        put("description", "true — только проверить применимость (git apply --check) " +
                            "и не менять файлы; по умолчанию false.")
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
                    add(JsonPrimitive("dir"))
                    add(JsonPrimitive("patchPath"))
                }
            },
        ),
        Tool(
            name = "repo_run",
            description = "Выполнить произвольную команду в каталоге репозитория на SSH-сервере " +
                "(server или server_id): dir — путь к репозиторию, command — команда, например " +
                "git pull, npm test или make. Выполняется как `cd <dir> && <command>` через тот же " +
                "неинтерактивный SSH-канал, что и ssh_exec: без PTY и без ввода пароля (sudo, " +
                "спрашивающий пароль, не сработает). Возвращаются код возврата, stdout и stderr " +
                "(ограничены по размеру). timeoutMs — бюджет в миллисекундах (по умолчанию 60000, " +
                "максимум 300000). Изменяет состояние на сервере.",
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
                    putJsonObject("dir") {
                        put("type", "string")
                        put("description", "Путь к репозиторию на сервере (например ~/project).")
                    }
                    putJsonObject("command") {
                        put("type", "string")
                        put("description", "Команда оболочки для выполнения в каталоге репозитория.")
                    }
                    putJsonObject("timeoutMs") {
                        put("type", "string")
                        put("description", "Таймаут в миллисекундах (по умолчанию 60000, максимум 300000).")
                    }
                    putJsonObject("trust_host") {
                        put("type", "boolean")
                        put("description", "Запомнить ключ хоста, если ещё не подтверждён.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("dir"))
                    add(JsonPrimitive("command"))
                }
            },
        ),
    )
}
