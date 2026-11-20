package com.andharness.ru.domain.notifications

import kotlinx.serialization.Serializable

/**
 * User-defined rules for how Меха treats captured notifications (checklist §1, «Умный инбокс
 * уведомлений»): per-app priority, quiet hours and how much the digest shows.
 *
 * The whole model is deliberately **Android-free** (only `kotlinx.serialization` + `java.time`), so
 * the pure policy ([NotificationRules.classify] / [NotificationRules.isQuiet]) and its codec are
 * exercised on the JVM; the Android-bound [com.andharness.ru.data.notifications.NotificationRulesStore]
 * only adds file I/O on top.
 *
 * Semantics mirror the platform's own notification model:
 * - importance levels follow `NotificationManager.IMPORTANCE_*` (LOW/MIN = silent, DEFAULT = normal,
 *   HIGH = peeks) — https://developer.android.com/reference/android/app/NotificationManager#IMPORTANCE_HIGH
 *   and `NotificationChannel.getImportance()`
 *   https://developer.android.com/reference/android/app/NotificationChannel#getImportance()
 * - quiet hours are the app's own, non-privileged analogue of Do Not Disturb priority mode
 *   (`NotificationManager.INTERRUPTION_FILTER_PRIORITY`, "all notifications are suppressed except
 *   those the user has identified as important") and of a channel's `canBypassDnd()`
 *   https://developer.android.com/reference/android/app/NotificationManager#INTERRUPTION_FILTER_PRIORITY
 *   https://developer.android.com/reference/android/app/NotificationChannel#canBypassDnd()
 *
 * Меха never changes the system notification itself (it only observes via
 * `NotificationListenerService`): a MUTE rule stops Меха from surfacing/forwarding the event, it does
 * not dismiss or silence the real status-bar notification.
 */

@Serializable
enum class RuleImportance {
    /** Never surfaced by Меха (no digest entry, no memory note). The system notification is untouched. */
    MUTE,

    /** Default: everything works exactly as without a rule. */
    NORMAL,

    /** Always treated as important: forced to HIGH by the triage and never suppressed by quiet hours. */
    IMPORTANT,
}
