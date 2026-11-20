package com.andharness.ru.data.intercept

import android.content.Context
import android.util.Log
import com.andharness.ru.data.calls.CallRulesStore
import com.andharness.ru.data.message.MessageSendOutcome
import com.andharness.ru.data.message.MessageSender
import com.andharness.ru.data.schedule.ScheduledTaskActions
import com.andharness.ru.domain.calls.CallBackReminderPolicy
import com.andharness.ru.domain.calls.CallRuleEngine
import com.andharness.ru.domain.calls.SmsAutoReplyPolicy
import com.andharness.ru.domain.calls.SmsReplyDecision
import com.andharness.ru.domain.schedule.ScheduleRepository
import com.andharness.ru.domain.schedule.ScheduledTask
import com.andharness.ru.domain.sms.SmsPhotoRuleEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Android integration point that makes the stored call/SMS rules **take effect** (checklist §1).
 *
 * It is deliberately thin: every decision is taken by the pure
 * [com.andharness.ru.domain.calls.CallRuleEngine] / [SmsAutoReplyPolicy] /
 * [CallBackReminderPolicy]; this class only reads the real signals and performs the side effects
 * (send an SMS via the existing [MessageSender], or create a one-shot reminder via the existing
 * schedule path).
 *
 * Safety:
 * - **strictly opt-in** — every entry point returns immediately with `acted = false` when
 *   [CallRules.enabled] is `false`; the default state changes nothing;
 * - **never replies to** allowlisted contacts, short codes, service/emergency numbers
 *   ([CallRuleEngine.isReplyEligible]) or an SMS-photo command (a separate feature);
 * - **per-number cooldown** (default 6 h, [CallRulesStore.isAutoReplyCoolingDown]) so a duplicated
 *   broadcast or a repeated call can never cause a reply loop;
 * - **honest failure** — a missing `SEND_SMS` / `SmsManager` produces a `Failed` note, never a fake
 *   success.
 *
 * References:
 * - `SmsManager.sendTextMessage` (through [MessageSender]) and the platform's premium/short-code
 *   restriction:
 *   https://developer.android.com/reference/android/telephony/SmsManager#sendTextMessage(java.lang.String,%20java.lang.String,%20java.lang.String,%20android.app.PendingIntent,%20android.content.Intent)
 * - `CallScreeningService` (the caller of [autoReplyToCaller]):
 *   https://developer.android.com/reference/android/telecom/CallScreeningService
 */
@Singleton
class CallSmsRuleApplier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: CallRulesStore,
    private val messageSender: MessageSender,
    private val scheduleRepository: ScheduleRepository,
    private val actions: ScheduledTaskActions,
) {

    /** Whether anything was done, plus an honest, log-readable note. */
    data class Outcome(val acted: Boolean, val note: String)

    /**
     * Handles one inbound SMS for the auto-reply rule. Never throws for ordinary failures.
     * `acted = false` covers both "feature disabled" (a strict no-op) and "decided not to reply".
     */
    suspend fun onIncomingSms(
        sender: String,
        body: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Outcome {
        val rules = store.current()
        if (!rules.enabled) return Outcome(false, "авто-ответ SMS выключен (enabled=false) — no-op")

        val normalized = CallRuleEngine.normalizeNumber(sender)
        val signals = SmsAutoReplyPolicy.Signals(
            allowlisted = normalized != null && CallRuleEngine.contains(rules.allowNumbers, normalized),
            numberKnown = ContactLookup.isKnown(context, sender),
            inCall = BusySignal.isInCall(context),
            dndActive = BusySignal.isDndActive(context),
            replyEligible = CallRuleEngine.isReplyEligible(sender),
            coolingDown = store.isAutoReplyCoolingDown(sender, nowMs),
            photoCommand = SmsPhotoRuleEngine.matchesPhotoCommand(body),
        )
        val plan = SmsAutoReplyPolicy.decide(rules, signals)
        if (plan.decision != SmsReplyDecision.REPLY) {
            return Outcome(false, plan.reason)
        }
        return sendAutoReply(sender, rules.autoReplyText.trim(), plan.reason, nowMs)
    }

    /**
     * Sends the configured auto-reply to a **caller** whose call was allowed-and-replied or rejected
     * as unknown spam. Applies the same eligibility/cooldown guards as the SMS path.
     */
    suspend fun autoReplyToCaller(
        rawNumber: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Outcome {
        val rules = store.current()
        if (!rules.enabled) return Outcome(false, "авто-обработка выключена (enabled=false) — no-op")
        if (rules.autoReplyText.isBlank()) return Outcome(false, "текст авто-ответа пуст")
        if (!CallRuleEngine.isReplyEligible(rawNumber)) {
            return Outcome(false, "номер не может получать авто-ответ (служебный/короткий/экстренный)")
        }
        if (store.isAutoReplyCoolingDown(rawNumber, nowMs)) {
            return Outcome(false, "кулдаун на номер ещё не истёк")
        }
        return sendAutoReply(rawNumber, rules.autoReplyText.trim(), "звонок обработан правилами", nowMs)
    }

    /**
     * Creates the one-shot call-back reminder for a rejected/missed call from a known contact, when
     * [com.andharness.ru.domain.calls.CallRules.callBackReminder] is on. Reuses the existing schedule
     * path, so the reminder lives in «Задачи по расписанию» and fires through the normal worker.
     */
    suspend fun maybeCreateCallBackReminder(
        rawNumber: String,
        contactName: String?,
        rejectedOrMissed: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): Outcome {
        val rules = store.current()
        if (!CallBackReminderPolicy.shouldCreate(rules, contactKnown = contactName != null, rejectedOrMissed = rejectedOrMissed)) {
            return Outcome(false, "напоминание перезвонить не требуется")
        }
        val atMs = CallBackReminderPolicy.reminderAtMs(nowMs)
        val local = Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault())
        val text = CallBackReminderPolicy.reminderText(contactName, rawNumber)
        return try {
            val id = scheduleRepository.create(
                ScheduledTask(
                    id = 0,
                    prompt = text,
                    // The DB columns are NOT NULL; for a one-shot task these are derived from atMs
                    // and never used for scheduling (see ScheduledTask.isOneShot).
                    hour = local.hour,
                    minute = local.minute,
                    daysMask = ScheduledTask.EVERY_DAY,
                    enabled = true,
                    lastRunAt = 0,
                    chatId = null,
                    atMs = atMs,
                ),
            )
            val task = scheduleRepository.getById(id)
            if (task != null) actions.schedule(task)
            Log.i(TAG, "call-back reminder created for $rawNumber at $atMs")
            Outcome(true, "создано напоминание #$id «$text»")
        } catch (e: Exception) {
            Log.w(TAG, "call-back reminder creation failed", e)
            Outcome(false, "напоминание не создано: ${e.message}")
        }
    }

    private suspend fun sendAutoReply(
        number: String,
        text: String,
        reason: String,
        nowMs: Long,
    ): Outcome = when (val result = messageSender.sendSms(number, text)) {
        is MessageSendOutcome.Sent -> {
            // Record the cooldown only on a platform-confirmed send, so a failed attempt is retried.
            store.noteAutoReply(number, nowMs)
            Outcome(true, "авто-ответ отправлен ($reason): ${result.detail}")
        }
        is MessageSendOutcome.Failed -> Outcome(true, "авто-ответ не отправлен ($reason): ${result.reason}")
        is MessageSendOutcome.Opened -> Outcome(true, "авто-ответ не отправлен ($reason): SMS требует прямого пути")
    }

    private companion object {
        const val TAG = "Andharness"
    }
}
