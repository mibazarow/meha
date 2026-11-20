package com.andharness.ru.data.tools.diagnostics

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import com.andharness.ru.data.core.WatchdogConfigStore
import com.andharness.ru.data.core.WatchdogState
import com.andharness.ru.data.crash.RestartPolicy
import com.andharness.ru.data.diagnostics.PowerGuard
import com.andharness.ru.data.diagnostics.ProcessExitInfo
import com.andharness.ru.data.diagnostics.ProcessExitReader
import com.andharness.ru.data.tools.formatBytes
import com.andharness.ru.di.DatabaseModule
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.assist.AssistRepository
import com.andharness.ru.domain.automation.AutomationRuleRepository
import com.andharness.ru.domain.crash.CrashLog
import com.andharness.ru.domain.crash.CrashLogRepository
import com.andharness.ru.domain.crash.RestartStateRepository
import com.andharness.ru.domain.diagnostics.ExitReasonCatalog
import com.andharness.ru.domain.tools.ToolHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import com.andharness.ru.data.tools.str
import com.andharness.ru.data.tools.formatTimestamp
import com.andharness.ru.data.tools.formatDuration

/**
 * Diagnostics tools for the crash-protection core (update.md §1.4 / §8) plus self-observability
 * (update.md idea #19): [crash_log_list], [crash_log_clear], [self_diagnostics], [exit_reasons],
 * [power_guard_status]. None of them needs a permission and none is dangerous — they only read
 * system state.
 *
 * All of them are deliberately HONEST: an unavailable platform API (exit history below API 30) or an
 * unreadable signal (battery/thermal) is reported as unavailable instead of being guessed, and the
 * power guard explains both the decision and its reason.
 */
class DiagnosticsToolsHandler(
    @ApplicationContext private val context: Context,
    private val crashLogs: CrashLogRepository,
    private val restartState: RestartStateRepository,
    private val config: WatchdogConfigStore,
    private val automationRules: AutomationRuleRepository,
    private val processExits: ProcessExitReader,
    private val powerGuard: PowerGuard,
    private val assistRepository: AssistRepository
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "crash_log_list"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val requested = arguments.str("limit")?.toIntOrNull()
                    val limit = (requested ?: DEFAULT_LIST_LIMIT)
                        .coerceIn(1, CrashLogRepository.MAX_ENTRIES)
                    val total = crashLogs.count()
                    if (total == 0) {
                        return@withContext ToolResult(true, "Крэш-логов нет: приложение ещё не падало.")
                    }
                    val logs = crashLogs.latest(limit)
                    val body = logs.joinToString("\n\n") { format(it) }
                    ToolResult(
                        true,
                        "Крэш-логи (показано ${logs.size} из $total; хранится не более " +
                            "${CrashLogRepository.MAX_ENTRIES} записей и не дольше 30 дней):\n\n$body"
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка чтения крэш-логов: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "crash_log_clear"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val removed = crashLogs.clear()
                    ToolResult(true, "Крэш-логи очищены (удалено записей: $removed).")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка очистки крэш-логов: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "exit_reasons"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    if (!processExits.supported) {
                        return@withContext ToolResult(
                            true,
                            "История выходов процесса НЕДОСТУПНА: нужен Android 11 (API 30)+, " +
                                "а на устройстве API ${Build.VERSION.SDK_INT}. Честно: система не " +
                                "хранит причины завершения процесса на этой версии."
                        )
                    }
                    val requested = arguments.str("limit")?.toIntOrNull()
                    val limit = (requested ?: DEFAULT_EXIT_LIMIT)
                        .coerceIn(1, ProcessExitReader.MAX_LIMIT)
                    val entries = processExits.recent(limit)
                    if (entries.isEmpty()) {
                        return@withContext ToolResult(
                            true,
                            "История выходов процесса пуста: система не сохранила ни одной записи " +
                                "(API ${Build.VERSION.SDK_INT})."
                        )
                    }
                    val failures = entries.count { ExitReasonCatalog.isFailure(it.reasonCode) }
                    ToolResult(
                        true,
                        buildString {
                            appendLine(
                                "История выходов процесса (ApplicationExitInfo), показано " +
                                    "${entries.size} из лимита $limit, API ${Build.VERSION.SDK_INT}:"
                            )
                            entries.forEachIndexed { index, entry ->
                                appendLine(formatExit(index + 1, entry))
                            }
                            append("сбоев среди показанных: $failures из ${entries.size}")
                        }
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка чтения истории выходов процесса: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "power_guard_status"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    ToolResult(
                        true,
                        buildString {
                            appendLine("Охрана питания Меха")
                            appendLine(powerGuard.statusReport())
                            append(
                                "под охраной: необязательная периодическая фоновая работа " +
                                    "(обновление фактов об устройстве, фоновый индекс персон, " +
                                    "правила автоматизации по времени, отложенный перезапуск после " +
                                    "сбоя). Запуски, которые запросил пользователь, не откладываются."
                            )
                        }
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка чтения состояния питания: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "self_diagnostics"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val status = WatchdogState.status.value
                    val coreLine = if (status.running) {
                        "ядро: запущено${status.reason?.let { " ($it)" } ?: ""}, с ${formatTime(status.startedAt)}"
                    } else {
                        "ядро: НЕ запущено, причина: ${status.reason ?: WatchdogState.REASON_NOT_STARTED}"
                    }
                    val lastCrash = crashLogs.mostRecent()
                    val crashLine = if (lastCrash == null) {
                        "последний крэш: нет"
                    } else {
                        "последний крэш: ${formatTime(lastCrash.at)} — ${lastCrash.exceptionType} " +
                            "(перезапуск #${lastCrash.restartNumber})"
                    }
                    val restart = restartState.get()
                    val restartLine = "перезапуски в текущем окне (1 ч, максимум " +
                        "${RestartPolicy.MAX_RESTARTS_PER_WINDOW}): ${restart.restartCount}" +
                        if (restart.lastRestartAt > 0) ", последний: ${formatTime(restart.lastRestartAt)}" else ""

                    ToolResult(
                        true,
                        buildString {
                            appendLine("Диагностика Меха")
                            appendLine("аптайм процесса: ${formatDuration(uptimeMs())}")
                            appendLine("версия: ${deviceSummary()}")
                            appendLine(coreLine)
                            appendLine(crashLine)
                            appendLine(restartLine)
                            appendLine("подсистемы: ядро=${onOff(config.isCoreEnabled())}, " +
                                "перехват=${onOff(config.isInterceptionEnabled())}, " +
                                "правила автоматизации=${onOff(hasEnabledAutomationRules())}")
                            appendLine("спец-доступы: батарея без оптимизации=" +
                                "${onOff(powerGuard.snapshot().batteryOptimizationExempt)}, " +
                                "точные будильники=${onOff(canScheduleExactAlarms())}, " +
                                "уведомления=${onOff(notificationsEnabled())}")
                            appendLine(compact(powerGuard.statusReport()))
                            appendLine(exitSummaryLine())
                            appendLine(memorySummaryLine())
                            append("всего крэш-логов: ${crashLogs.count()}")
                        }
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка диагностики: ${e.message}")
                }
            }
        },
    )

    private fun format(log: CrashLog): String = buildString {
        append("#${log.id} | ${formatTime(log.at)} | ${log.exceptionType}")
        appendLine()
        append("перезапуск #${log.restartNumber}, ${if (log.wasForeground) "на переднем плане" else "в фоне"}, ")
        append("${log.deviceModel} (API ${log.apiLevel}), версия ${log.appVersion.ifBlank { "?" }}")
        appendLine()
        append(STACK_PREVIEW_INDENT + log.stack.replace("\n", "\n" + STACK_PREVIEW_INDENT))
    }

    /** One exit-history entry, fully honest about missing pieces (no description / no memory data). */
    private fun formatExit(index: Int, entry: ProcessExitInfo): String = buildString {
        append("$index) ${formatTime(entry.timestampMs)} — ${entry.reasonLabel} ")
        append("(код ${entry.reasonCode}, ${ExitReasonCatalog.category(entry.reasonCode)})")
        val memory = buildList {
            if (entry.pssKb >= 0) add("pss ${entry.pssKb} КБ")
            if (entry.rssKb >= 0) add("rss ${entry.rssKb} КБ")
        }
        appendLine()
        append("   важно: ${entry.importance}")
        if (memory.isNotEmpty()) append(", ${memory.joinToString(", ")}") else append(", память: недоступна")
        entry.processName?.takeIf { it.isNotBlank() }?.let { append(", процесс: $it") }
        entry.description?.let { appendLine().append("   описание: ${it.replace("\n", " ")}") }
    }

    /** Compact one-line exit-history summary for [self_diagnostics]. */
    private fun exitSummaryLine(): String {
        if (!processExits.supported) {
            return "история выходов: недоступна (нужен API 30+, сейчас API ${Build.VERSION.SDK_INT})"
        }
        val entries = processExits.recent(DIAGNOSTIC_EXIT_LIMIT)
        if (entries.isEmpty()) return "история выходов: пуста (система не сохранила записей)"
        val last = entries.first()
        val failures = entries.count { ExitReasonCatalog.isFailure(it.reasonCode) }
        return "история выходов: последний ${formatTime(last.timestampMs)} — ${last.reasonLabel}; " +
            "сбоев $failures из ${entries.size} последних"
    }

    /** Collapses a multi-line report into one line so the diagnostics block stays compact. */
    private fun compact(text: String): String =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("; ")

    /**
     * Compact, cheap memory section: aggregate counts only (`COUNT(*)`/`GROUP BY`, no row scan) plus
     * the on-disk database size. A count or file-stat failure is reported honestly, never guessed.
     */
    private suspend fun memorySummaryLine(): String = try {
        val stats = assistRepository.stats()
        val importance = if (stats.memoryCount == 0) "нет записей"
        else MEMORY_IMPORTANCE_LEVELS.joinToString(", ") { level ->
            "$level=${stats.memoryByImportance[level] ?: 0}"
        }
        "память: заметок=${stats.noteCount}, долговременных=${stats.memoryCount} " +
            "(важность: $importance), БД: ${databaseSizeLabel()}"
    } catch (e: Exception) {
        "память: недоступно (${e.message})"
    }

    /** On-disk size of the (encrypted) Room database file, or an honest "not found". */
    private fun databaseSizeLabel(): String = try {
        val file = context.getDatabasePath(DatabaseModule.DATABASE_NAME)
        if (file.exists()) formatBytes(file.length()) else "файл не найден"
    } catch (e: Exception) {
        "размер недоступен"
    }

    /**
     * The core's third subsystem: the service starts when at least one automation rule is enabled,
     * so diagnostics must report the real state of that rule set rather than a guess.
     */
    private suspend fun hasEnabledAutomationRules(): Boolean = try {
        automationRules.getAll().any { it.enabled }
    } catch (e: Exception) {
        false
    }

    private fun uptimeMs(): Long = try {
        SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
    } catch (e: Exception) {
        0L
    }

    private fun deviceSummary(): String =
        "Android API ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return try {
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        } catch (e: Exception) {
            false
        }
    }

    private fun notificationsEnabled(): Boolean = try {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    } catch (e: Exception) {
        false
    }

    private fun onOff(value: Boolean): String = if (value) "да" else "нет"

    private fun formatTime(ms: Long): String = formatTimestamp(ms, "dd.MM.yyyy HH:mm:ss")


    companion object {
        private const val DEFAULT_LIST_LIMIT = 10

        /** Default number of exit-history entries for the dedicated tool. */
        private const val DEFAULT_EXIT_LIMIT = 20

        /** How many exit-history entries the compact [self_diagnostics] summary inspects. */
        private const val DIAGNOSTIC_EXIT_LIMIT = 10
        private const val STACK_PREVIEW_INDENT = "    "

        /** Importance levels reported by the memory histogram in [self_diagnostics]. */
        private val MEMORY_IMPORTANCE_LEVELS = listOf(0, 1, 2)
    }
}
