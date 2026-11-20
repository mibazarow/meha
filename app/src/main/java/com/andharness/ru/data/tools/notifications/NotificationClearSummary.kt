package com.andharness.ru.data.tools.notifications

/**
 * Pure, JVM-testable formatting of the `notification_clear_all` result: how many active
 * notifications were requested to be dismissed and how many are still active afterwards.
 * The listener's `cancelNotification` is asynchronous, so the handler reports the residual count
 * honestly instead of claiming success.
 *
 * Reference: `NotificationListenerService.cancelNotification(String)` /
 * `getActiveNotifications()`:
 * https://developer.android.com/reference/android/service/notification/NotificationListenerService
 */
internal object NotificationClearSummary {

    fun format(total: Int, remaining: Int): String = when {
        total <= 0 -> "Активных уведомлений нет."
        remaining <= 0 -> "Снято уведомлений: $total."
        else ->
            "Снято ${total - remaining} из $total; $remaining остались " +
                "(вероятно, постоянные/неубираемые или доступ к уведомлениям отключён)."
    }
}
