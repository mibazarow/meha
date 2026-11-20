package com.andharness.ru.data.tools.notifications

import android.content.Context
import android.content.pm.ApplicationInfo
import com.andharness.ru.data.notifications.NotificationRulesStore
import com.andharness.ru.data.notify.NotificationTriageEntryPoint
import com.andharness.ru.data.notify.notificationTriageEntryPoint
import com.andharness.ru.data.tools.boolOrNull
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.notify.NotificationCaptureLog
import com.andharness.ru.domain.notify.NotificationDigest
import com.andharness.ru.domain.notify.NotificationEvent
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Builds the handlers of idea #17:
 * - `notifications_digest` — grouped digest of the recently captured notifications, important ones
 *   marked (heuristic, built locally without a model);
 * - `notification_triage_simulate` — debug-only seam that feeds one synthetic event through the
 *   real triage engine, used to verify urgent → memory and OTP/bulk → skipped on a device without
 *   being able to post notifications as a third-party messenger.
 *
 * The memorization policy is fixed to [com.andharness.ru.domain.notify.MemorizeMode.ALL] (HIGH and
 * NORMAL are memorized, LOW is not); one-time codes are never memorized.
 *
 * Honesty rules: the digest reports the real buffer contents (or an explicit empty state), and the
 * simulation tool refuses to run in a non-debuggable build.
 *
 * User rules (checklist §1): the digest's `summaryMode` comes from [rulesStore], and the triage engine
 * itself applies the per-app importance / quiet-hours rules.
 */
class NotificationTriageToolsHandler(
    private val context: Context,
    private val captureLog: NotificationCaptureLog,
    private val rulesStore: NotificationRulesStore,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "notifications_digest"

            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val limit = (arguments.str("limit")?.toIntOrNull() ?: NotificationDigest.DEFAULT_LIMIT)
                        .coerceIn(1, NotificationCaptureLog.MAX_ENTRIES)
                    val since = resolveSince(arguments.str("since"))
                    val entries = captureLog.recent(since = since, limit = limit)
                    ToolResult(true, NotificationDigest.build(entries, limit, rulesStore.current().summaryMode))
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка сводки уведомлений: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "notification_triage_simulate"

            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                if (!isDebuggable()) {
                    return@withContext ToolResult(false, "Инструмент доступен только в debug-сборке.")
                }
                try {
                    val event = NotificationEvent(
                        app = arguments.str("package") ?: return@withContext ToolResult(false, "Укажите package."),
                        sender = arguments.str("sender"),
                        title = arguments.str("title").orEmpty(),
                        text = arguments.str("text").orEmpty(),
                        isMissedCall = arguments.bool("missed_call"),
                        isGroupOrBulk = arguments.bool("group"),
                    )
                    val engine = entryPoint().notificationTriageEngine()
                    val outcome = engine.onEvent(event)
                    val result = outcome.result
                    val lines = buildString {
                        appendLine("app: ${event.app}")
                        if (result == null) {
                            appendLine("результат: не триажен (${outcome.skipped})")
                        } else {
                            appendLine("важность: ${result.importance}")
                            appendLine("категория: ${result.category}")
                            appendLine("секрет: ${result.secret}")
                            appendLine("сводка: ${result.summary}")
                            appendLine("причины: ${result.reasons.joinToString("; ").ifEmpty { "—" }}")
                            appendLine("захвачено: ${outcome.captured}")
                            appendLine("запомнено: ${outcome.memorized}${outcome.noteId?.let { " (#$it)" } ?: ""}")
                            appendLine("пропуск: ${outcome.skipped ?: "—"}")
                        }
                    }
                    ToolResult(true, lines.trim())
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка симуляции: ${e.message}")
                }
            }
        },
    )

    /** `since` is minutes-ago for small values and epoch millis for large ones; `null` = no floor. */
    private fun resolveSince(raw: String?): Long? {
        val value = raw?.trim()?.toLongOrNull() ?: return null
        if (value <= 0L) return null
        return if (value > EPOCH_MILLIS_THRESHOLD) value else System.currentTimeMillis() - value * 60_000L
    }

    private fun entryPoint(): NotificationTriageEntryPoint = notificationTriageEntryPoint(context)

    private fun isDebuggable(): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun JsonObject.bool(key: String): Boolean = boolOrNull(key) == true

    private companion object {
        /** Anything above this is treated as an epoch-millis timestamp, not a minute count. */
        const val EPOCH_MILLIS_THRESHOLD = 10_000_000_000L
    }
}
