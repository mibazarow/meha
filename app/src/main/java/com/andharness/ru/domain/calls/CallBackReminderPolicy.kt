package com.andharness.ru.domain.calls


/**
 * Pure policy for the call-back reminder ([CallRules.callBackReminder]): a **known** contact whose
 * call was rejected by the rules or missed must produce one one-shot reminder to call back.
 *
 * The Android layer reuses the existing schedule/reminder creation path, so the reminder shows up in
 * «Задачи по расписанию» exactly like `reminder_set`.
 */
object CallBackReminderPolicy {

    /** Default delay before the reminder fires: 30 minutes after the missed/rejected call. */
    const val DEFAULT_DELAY_MS: Long = 30L * 60L * 1000L

    /**
     * `true` only when the whole feature is [CallRules.enabled], the [CallRules.callBackReminder] flag
     * is on, the caller is a **known contact** and the call was actually rejected or missed. An
     * explicit `false` from any of those is a strict no-op.
     */
    fun shouldCreate(rules: CallRules, contactKnown: Boolean, rejectedOrMissed: Boolean): Boolean =
        rules.enabled && rules.callBackReminder && contactKnown && rejectedOrMissed

    /** The reminder title/text: the contact name when known, otherwise the number. */
    fun reminderText(contactName: String?, number: String): String {
        val who = contactName?.trim()?.takeIf { it.isNotEmpty() } ?: number
        return "Перезвонить: $who"
    }

    /** Epoch-millis at which the reminder should fire; a negative delay is clamped to "now". */
    fun reminderAtMs(nowMs: Long, delayMs: Long = DEFAULT_DELAY_MS): Long =
        nowMs + delayMs.coerceAtLeast(0L)
}
