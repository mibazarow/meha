package com.andharness.ru.data.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Semantic outcome of an SMS/MMS SENT broadcast, independent of the caller's own result type.
 *
 * The platform reports the send result as the [BroadcastReceiver.getResultCode] of the SENT
 * broadcast; [SmsSentResultAwait.outcomeFor] is the single, pure mapping from that raw code to one
 * of these outcomes. Each call site then renders its own (unchanged) user-facing message, so the
 * distinct wording of `MessageSender` and the `data/tools/sms` tools is preserved.
 * https://developer.android.com/reference/android/telephony/SmsManager
 */
internal sealed interface SmsSentOutcome {
    data object Ok : SmsSentOutcome
    data object Timeout : SmsSentOutcome
    data object RadioOff : SmsSentOutcome
    data object NoService : SmsSentOutcome
    data object NullPdu : SmsSentOutcome
    data class GenericFailure(val code: Int) : SmsSentOutcome
}

/**
 * The single implementation of the "mutable SENT `PendingIntent` + nonce receiver + await result"
 * pattern shared by [com.andharness.ru.data.message.MessageSender] and the `data/tools/sms` sending
 * tools (`sms_send`, `sms_send_on_sim`, `mms_send`).
 *
 * The two former copies differed ONLY in the action-name prefix; the prefix is therefore a
 * parameter and every other detail (flags, timeout handling, receiver registration, cleanup) is
 * identical and lives here once.
 *
 * The SENT broadcast is emitted by the telephony stack (a different app/uid, e.g. the phone
 * process), not by this app. On Android 14+ (API 34+) a dynamically registered receiver must
 * declare its export state, and `RECEIVER_NOT_EXPORTED` only receives broadcasts sent by the same
 * app (or the system acting on its behalf); a cross-uid broadcast is silently dropped. The
 * sent-result receiver is therefore registered as `RECEIVER_EXPORTED`, and since it is exported it
 * accepts ONLY the exact action of this send (carrying an unguessable nonce); every other broadcast
 * is ignored.
 * https://developer.android.com/about/versions/14/behavior-changes-14#runtime-receivers-exported
 * https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int)
 */
internal object SmsSentResultAwait {

    /**
     * Action prefix historically used by `MessageSender` (`…COMPOSE_SMS_SENT.<nonce>`).
     * MUST stay distinct from [PREFIX_SMS_SENT] to preserve each call site's action name (and any
     * external consumer that filters on it).
     */
    const val PREFIX_COMPOSE_SMS_SENT = "COMPOSE_SMS_SENT"

    /** Action prefix historically used by the `data/tools/sms` sending tools (`…SMS_SENT.<nonce>`). */
    const val PREFIX_SMS_SENT = "SMS_SENT"

    /**
     * Pure builder for the per-send action name. The nonce makes the exported receiver's accepted
     * broadcast unguessable, so only the matching SENT result is accepted.
     */
    fun sentAction(packageName: String, actionPrefix: String, nonce: String): String =
        "$packageName.$actionPrefix.$nonce"

    /**
     * Pure mapping from the raw SENT result code to a [SmsSentOutcome]. `null` means the caller's
     * timeout elapsed before any broadcast arrived.
     */
    fun outcomeFor(resultCode: Int?): SmsSentOutcome = when {
        resultCode == null -> SmsSentOutcome.Timeout
        resultCode == Activity.RESULT_OK -> SmsSentOutcome.Ok
        resultCode == SmsManager.RESULT_ERROR_RADIO_OFF -> SmsSentOutcome.RadioOff
        resultCode == SmsManager.RESULT_ERROR_NO_SERVICE -> SmsSentOutcome.NoService
        resultCode == SmsManager.RESULT_ERROR_NULL_PDU -> SmsSentOutcome.NullPdu
        else -> SmsSentOutcome.GenericFailure(resultCode)
    }

    /**
     * Registers a one-shot receiver for a unique SENT action, invokes [send] with the SENT
     * [PendingIntent], and waits for the telephony stack's broadcast. Returns the raw result code,
     * or `null` on timeout (a timeout is never success).
     *
     * @param actionPrefix one of [PREFIX_COMPOSE_SMS_SENT] / [PREFIX_SMS_SENT]; keeps each call
     *   site's observable action name unchanged.
     */
    suspend fun await(
        context: Context,
        actionPrefix: String,
        timeoutMs: Long,
        send: (PendingIntent) -> Unit,
    ): Int? {
        val nonce = "${System.currentTimeMillis()}_${UUID.randomUUID()}"
        val action = sentAction(context.packageName, actionPrefix, nonce)
        val signal = CompletableDeferred<Int>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                // Exported receiver: accept ONLY this send's exact action; ignore anything else.
                if (intent?.action == action && !signal.isCompleted) {
                    signal.complete(resultCode)
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_EXPORTED,
        )
        // The telephony stack must be able to attach the send result (result code / error extras)
        // to the sentIntent, so on Android 12+ the PendingIntent MUST be mutable: an immutable one
        // cannot be filled in by the system, the SENT broadcast is never delivered and the send is
        // wrongly reported as a timeout although the SMS went out. FLAG_MUTABLE exists from API 31;
        // below that no mutability flag is required.
        // https://developer.android.com/about/versions/12/behavior-changes-12#pending-intent-mutability
        // https://developer.android.com/reference/android/app/PendingIntent#FLAG_MUTABLE
        val sentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getBroadcast(
            context,
            0,
            Intent(action).setPackage(context.packageName),
            sentFlags,
        )
        return try {
            send(pending)
            withTimeoutOrNull(timeoutMs) { signal.await() }
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
                // Already unregistered; nothing to do.
            }
        }
    }
}
