package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- update.md §1: crash protection diagnostics ----
internal object CrashDiagnosticsTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "crash_log_list",
            description = "Показать сохранённые крэш-логи (последние сбои приложения): время, тип исключения, " +
                "усечённый стек, модель/API, был ли агент на переднем плане, номер перезапуска. Хранится не " +
                "более 50 записей и не дольше 30 дней. limit — сколько последних записей показать (по умолчанию 10).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько последних записей показать (1–50, по умолчанию 10)")
                    }
                }
            },
        ),
        Tool(
            name = "crash_log_clear",
            description = "Очистить все сохранённые крэш-логи приложения. Возвращает число удалённых записей.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "self_diagnostics",
            description = "Диагностика: аптайм процесса, версия и модель устройства, последний крэш, счётчик " +
                "перезапусков в текущем часовом окне, состояние фонового ядра (запущено/нет и причина), " +
                "включённые подсистемы, статус спец-доступов (исключение из оптимизации батареи, точные " +
                "будильники, уведомления), состояние питания с решением power-guard и краткая сводка причин " +
                "последних выходов процесса. Честно сообщает, если ядро не запущено или данные недоступны.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "exit_reasons",
            description = "История причин завершения процесса приложения (ApplicationExitInfo, Android 11+): " +
                "время, причина (падение, ANR, убит системой из-за памяти, остановлен пользователем и т.д.), " +
                "важность, память (pss/rss) и описание от системы. limit — сколько последних записей показать " +
                "(1–64, по умолчанию 20). На Android ниже 11 честно сообщает, что история недоступна.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько последних записей показать (1–64, по умолчанию 20)")
                    }
                }
            },
        ),
        Tool(
            name = "power_guard_status",
            description = "Состояние питания и решение power-guard: заряд и зарядка, режим энергосбережения, " +
                "тепловой статус (нагрев), отключена ли оптимизация батареи для приложения, и откладывается ли " +
                "сейчас необязательная фоновая работа (при заряде ≤5% без зарядки или сильном нагреве). " +
                "Запуски, запрошенные пользователем, не откладываются.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "audit_export",
            description = "Экспортировать журнал аудита вызовов инструментов (append-only, хеш-цепочка) в файл " +
                "и вернуть путь. path — путь к файлу относительно корня общего хранилища (необязательно; по " +
                "умолчанию Documents/andharness_audit_<дата_время>.json). Если путь оканчивается на .csv — " +
                "сохраняется CSV. Инструмент также перепроверяет целостность хеш-цепочки и сообщает, не была " +
                "ли она нарушена.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к файлу экспорта (в общем хранилище; необязательно).")
                    }
                }
                putJsonArray("required") { }
            },
        ),
    )
}
