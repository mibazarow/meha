package com.andharness.ru.data.tools.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.service.NotificationRelayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.TimeUnit
import com.andharness.ru.data.tools.str

/**
 * Builds one [ToolHandler] per notification tool: [notification_list], [notification_open],
 * [notification_dismiss], [notification_clear_all], [notification_reply], [notification_extras],
 * [notification_snooze].
 * Requires the user to enable notification access.
 *
 * References (official Android docs):
 * - `NotificationListenerService` / `StatusBarNotification`:
 *   https://developer.android.com/reference/android/service/notification/StatusBarNotification
 * - `Notification.EXTRA_TITLE` / `EXTRA_TEXT`:
 *   https://developer.android.com/reference/android/app/Notification
 * - `RemoteInput` / `addResultsToIntent` for inline replies:
 *   https://developer.android.com/develop/ui/views/notifications/build-notification#reply-action
 * - Full notification content (title/text/bigText/subText + actions + `RemoteInput` presence) and
 *   `NotificationListenerService.snoozeNotification(String, long)`:
 *   https://developer.android.com/reference/android/service/notification/NotificationListenerService
 */
class NotificationListenerToolsHandler(
    private val context: Context,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "notification_list"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    if (!NotificationRelayService.isEnabled()) return@withContext notEnabled()
                    val items = NotificationRelayService.snapshot()
                    if (items.isEmpty()) return@withContext ToolResult(true, "Активных уведомлений нет.")
                    val lines = items.take(MAX_LIST).map { sbn ->
                        val title = sbn.notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                        val text = sbn.notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                        "${sbn.packageName} | $title | $text | key=${sbn.key}"
                    }
                    ToolResult(true, lines.joinToString("\n"))
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "notification_open"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                val sbn = resolve(arguments) ?: return@withContext notFoundOrDisabled()
                try {
                    val pi = sbn.notification.contentIntent
                        ?: return@withContext ToolResult(false, "У уведомления нет действия открытия.")
                    pi.send()
                    ToolResult(true, "Открыл уведомление от ${sbn.packageName}.")
                } catch (e: PendingIntent.CanceledException) {
                    ToolResult(false, "Действие уведомления больше недействительно.")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "notification_dismiss"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                val key = arguments.str("key")
                    ?: return@withContext ToolResult(false, "Укажите key (из notification_list).")
                val service = NotificationRelayService.instance
                    ?: return@withContext notEnabled()
                return@withContext try {
                    service.cancelNotification(key)
                    // cancelNotification is asynchronous: wait (bounded) for the notification to
                    // actually leave the active set before reporting success.
                    val removed = withTimeoutOrNull(DISMISS_VERIFY_TIMEOUT_MS) {
                        while (NotificationRelayService.find(key) != null) delay(100L)
                        true
                    } ?: false
                    if (removed) {
                        ToolResult(true, "Уведомление снято.")
                    } else {
                        ToolResult(
                            false,
                            "Уведомление всё ещё в списке активных через " +
                                "${DISMISS_VERIFY_TIMEOUT_MS / 1000} с — снятие не подтверждено " +
                                "(проверьте доступ к уведомлениям).",
                        )
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "notification_clear_all"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                // `NotificationManager.cancelAll` only touches our own notifications, so the sweep
                // must go through the listener: cancel each active notification by key.
                // https://developer.android.com/reference/android/service/notification/NotificationListenerService#cancelNotification(java.lang.String)
                val service = NotificationRelayService.instance
                    ?: return@withContext notEnabled()
                try {
                    val keys = NotificationRelayService.snapshot().map { it.key }
                    if (keys.isEmpty()) {
                        return@withContext ToolResult(true, NotificationClearSummary.format(0, 0))
                    }
                    for (key in keys) {
                        try {
                            service.cancelNotification(key)
                        } catch (e: Exception) {
                            // One uncancellable notification must not abort the whole sweep; the
                            // residual count below reports it honestly.
                        }
                    }
                    // cancelNotification is asynchronous: wait (bounded) for the active set to drain.
                    withTimeoutOrNull(CLEAR_ALL_VERIFY_TIMEOUT_MS) {
                        while (NotificationRelayService.snapshot().isNotEmpty()) delay(100L)
                    }
                    val remaining = NotificationRelayService.snapshot().size
                    ToolResult(remaining == 0, NotificationClearSummary.format(keys.size, remaining))
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "notification_reply"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                val sbn = resolve(arguments) ?: return@withContext notFoundOrDisabled()
                val text = arguments.str("text")
                    ?: return@withContext ToolResult(false, "Укажите text.")
                try {
                    val action = sbn.notification.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
                        ?: return@withContext ToolResult(false, "У этого уведомления нет поля для ответа.")
                    val remoteInput = action.remoteInputs!!.first()
                    val intent = Intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val bundle = Bundle().apply { putCharSequence(remoteInput.resultKey, text) }
                    RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)
                    action.actionIntent.send(context, 0, intent)
                    ToolResult(true, "Ответ отправлен.")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "notification_extras"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    if (!NotificationRelayService.isEnabled()) return@withContext notEnabled()
                    val key = arguments.str("key")
                    val pkg = arguments.str("package")
                    val target = when {
                        key != null -> NotificationRelayService.find(key)
                        pkg != null -> NotificationRelayService.snapshot().firstOrNull { it.packageName == pkg }
                        else -> null
                    }
                    if (target == null) {
                        val hint = if (key == null && pkg == null) {
                            " Укажите key (из notification_list) или package."
                        } else {
                            " Снимите notification_list и проверьте key/package."
                        }
                        return@withContext ToolResult(false, "Уведомление не найдено.$hint")
                    }
                    val notification = target.notification
                    val extras = notification.extras
                    val sb = StringBuilder()
                    sb.appendLine("Пакет: ${target.packageName}")
                    sb.appendLine("Key: ${target.key}")
                    sb.appendLine("Заголовок: ${extras?.getCharSequence(Notification.EXTRA_TITLE) ?: "—"}")
                    sb.appendLine("Текст: ${extras?.getCharSequence(Notification.EXTRA_TEXT) ?: "—"}")
                    sb.appendLine("Большой текст: ${extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: "—"}")
                    sb.appendLine("Подзаголовок: ${extras?.getCharSequence(Notification.EXTRA_SUB_TEXT) ?: "—"}")
                    sb.appendLine(
                        "Расширенный заголовок: " +
                            (extras?.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: "—"),
                    )
                    sb.appendLine("Категория: ${notification.category ?: "—"}")
                    val actions = notification.actions
                    if (actions.isNullOrEmpty()) {
                        sb.append("Действия: нет")
                    } else {
                        sb.appendLine("Действия (${actions.size}):")
                        actions.forEachIndexed { index, action ->
                            val inputs = action.remoteInputs?.takeIf { it.isNotEmpty() }
                            val remote = if (inputs == null) {
                                "без поля ответа"
                            } else {
                                "есть поле ответа (resultKey: " +
                                    inputs.joinToString(", ") { it.resultKey ?: "?" } + ")"
                            }
                            sb.appendLine("  ${index + 1}. ${action.title ?: "—"} | $remote")
                        }
                    }
                    ToolResult(true, sb.toString().trim())
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "notification_snooze"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val service = NotificationRelayService.instance ?: return@withContext notEnabled()
                    val key = arguments.str("key")
                        ?: return@withContext ToolResult(false, "Укажите key (из notification_list).")
                    val minutes = ((arguments["minutes"] as? JsonPrimitive)?.content?.trim()?.toIntOrNull()
                        ?: DEFAULT_SNOOZE_MINUTES).coerceIn(1, MAX_SNOOZE_MINUTES)
                    service.snoozeNotification(key, TimeUnit.MINUTES.toMillis(minutes.toLong()))
                    // snoozeNotification is fire-and-forget; wait briefly for the system to actually
                    // remove the notification from the active set and report honestly if it did not.
                    val removed = withTimeoutOrNull(SNOOZE_VERIFY_TIMEOUT_MS) {
                        while (NotificationRelayService.find(key) != null) delay(100L)
                        true
                    } ?: false
                    ToolResult(
                        true,
                        if (removed) {
                            "Уведомление отложено на $minutes мин."
                        } else {
                            "Запрос на отложение на $minutes мин. отправлен, но система не подтвердила " +
                                "удаление уведомления за ${SNOOZE_VERIFY_TIMEOUT_MS / 1000} с — возможно, " +
                                "доступ к уведомлениям отключён или уведомление уже снято."
                        },
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
    )

    private fun resolve(arguments: JsonObject): StatusBarNotification? {
        val key = arguments.str("key") ?: return null
        return NotificationRelayService.find(key)
    }

    private fun notFoundOrDisabled(): ToolResult =
        if (!NotificationRelayService.isEnabled()) notEnabled()
        else ToolResult(false, "Уведомление не найдено (снимите notification_list и укажите key).")

    private fun notEnabled(): ToolResult = ToolResult(
        false,
        com.andharness.ru.domain.permission.SpecialAccess.required(
            com.andharness.ru.domain.permission.SpecialAccess.NOTIFICATION_ACCESS
        )
    )


    private companion object {
        const val MAX_LIST = 40

        /** Default / maximum snooze window (minutes) for [notification_snooze]. */
        const val DEFAULT_SNOOZE_MINUTES = 15
        const val MAX_SNOOZE_MINUTES = 1440

        /** Bounded wait for the system to actually remove a snoozed notification. */
        const val SNOOZE_VERIFY_TIMEOUT_MS = 2_500L

        /** Bounded wait for `notification_dismiss` to confirm the notification left the set. */
        const val DISMISS_VERIFY_TIMEOUT_MS = 2_500L

        /** Bounded wait for `notification_clear_all` to confirm the active set drained. */
        const val CLEAR_ALL_VERIFY_TIMEOUT_MS = 3_000L
    }
}
