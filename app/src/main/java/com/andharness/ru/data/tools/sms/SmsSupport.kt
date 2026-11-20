package com.andharness.ru.data.tools.sms

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import com.andharness.ru.data.sms.SmsSentOutcome
import com.andharness.ru.data.sms.SmsSentResultAwait
import com.andharness.ru.domain.agent.ToolResult

/**
 * SmsManager lookup and SENT-broadcast plumbing shared by the sending tools (`sms_send`,
 * `sms_send_on_sim`, `mms_send`).
 *
 * The SENT/DELIVERED broadcast is emitted by the telephony stack (a different app/uid, e.g. the
 * phone process), not by this app. On Android 14+ (API 34+) a dynamically registered receiver must
 * declare its export state, and `RECEIVER_NOT_EXPORTED` only receives broadcasts sent by the same
 * app (or the system acting on its behalf); a cross-uid broadcast is silently dropped. The
 * sent-result receiver is therefore registered as `RECEIVER_EXPORTED`.
 * https://developer.android.com/about/versions/14/behavior-changes-14#runtime-receivers-exported
 * https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int)
 */
internal class SmsSupport(private val context: Context) {

    /**
     * The SmsManager for a specific subscription. `createForSubscriptionId` superseded
     * `getSmsManagerForSubscriptionId` in API 31 (the latter is deprecated but kept for API 26-30).
     * https://developer.android.com/reference/android/telephony/SmsManager#createForSubscriptionId(int)
     */
    fun smsManagerFor(subscriptionId: Int): SmsManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)?.createForSubscriptionId(subscriptionId)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }

    /**
     * Registers a one-shot receiver for a unique SENT action, waits for the broadcast that the
     * telephony stack delivers through the [PendingIntent], and returns its result code (or null on
     * timeout).
     *
     * Delegates to [SmsSentResultAwait.await], which gates acceptance on a per-send unguessable
     * nonce embedded in the action: only an intent whose action exactly matches this send's action
     * is accepted; anything else is ignored. This keeps the `…SMS_SENT.<nonce>` action name used by
     * the sending tools unchanged.
     * https://developer.android.com/reference/android/telephony/SmsManager#sendTextMessage(java.lang.String,%20java.lang.String,%20java.lang.String,%20android.app.PendingIntent,%20android.app.Intent)
     */
    suspend fun awaitSentResult(timeoutMs: Long, send: (PendingIntent) -> Unit): Int? =
        SmsSentResultAwait.await(
            context = context,
            actionPrefix = SmsSentResultAwait.PREFIX_SMS_SENT,
            timeoutMs = timeoutMs,
            send = send,
        )

    /** Maps the SENT broadcast result code to an honest Russian outcome. */
    fun sentResultToToolResult(code: Int?, target: String): ToolResult =
        when (SmsSentResultAwait.outcomeFor(code)) {
            SmsSentOutcome.Timeout ->
                ToolResult(false, "Таймаут: подтверждение отправки на $target не получено.")
            SmsSentOutcome.Ok ->
                ToolResult(true, "SMS отправлено на $target (подтверждено системой).")
            SmsSentOutcome.RadioOff ->
                ToolResult(false, "Отправка не удалась: радио выключено (код $code).")
            SmsSentOutcome.NoService ->
                ToolResult(false, "Отправка не удалась: нет сети (код $code).")
            SmsSentOutcome.NullPdu ->
                ToolResult(false, "Отправка не удалась: пустой PDU (код $code).")
            is SmsSentOutcome.GenericFailure -> ToolResult(
                false,
                "Отправка не удалась (код $code; RESULT_ERROR_GENERIC_FAILURE или неизвестная ошибка).",
            )
        }
}
