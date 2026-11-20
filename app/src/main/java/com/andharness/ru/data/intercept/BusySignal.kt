package com.andharness.ru.data.intercept

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * The "busy / unavailable" signal used by the SMS auto-reply (`autoReplyWhenBusy`).
 *
 * There is no single Android "user is busy" API, so the honest definition used here is:
 *  - **in a call** — on API 31+ `TelecomManager.isInCall()`, below that
 *    `TelephonyManager.getCallState()` is `OFFHOOK`/`RINGING`. Reading it requires `READ_PHONE_STATE`;
 *    when the permission is missing this returns `false` and logs the honest reason instead of
 *    guessing:
 *    https://developer.android.com/reference/android/telecom/TelecomManager#isInCall()
 *    https://developer.android.com/reference/android/telephony/TelephonyManager#getCallState()
 *  - **quiet / DND** — the current interruption filter is not `INTERRUPTION_FILTER_ALL`, i.e. the
 *    phone is silenced by a Do-Not-Disturb mode. Reading the current filter needs no special
 *    permission (only *changing* it does):
 *    https://developer.android.com/reference/android/app/NotificationManager#getCurrentInterruptionFilter()
 *
 * `busy` is the OR of the two. If neither signal can be read, `busy` is honestly `false` (no reply
 * is fabricated). The decision itself is pure and lives in
 * [com.andharness.ru.domain.calls.SmsAutoReplyPolicy]; this object only reads the platform state.
 */
object BusySignal {

    private const val TAG = "Andharness"

    data class State(val inCall: Boolean, val dndActive: Boolean) {
        val busy: Boolean get() = inCall || dndActive
    }

    /** Reads both signals; never throws. */
    fun read(context: Context): State = State(
        inCall = isInCall(context),
        dndActive = isDndActive(context),
    )

    /** `true` while a call is active or ringing; `false` (logged) when `READ_PHONE_STATE` is missing. */
    fun isInCall(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "busy signal: READ_PHONE_STATE не выдан — активный звонок не определяется (считаем «не занят»)")
            return false
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // API 31+: the modern, non-deprecated "is there an ongoing call" signal.
                context.getSystemService(TelecomManager::class.java)?.isInCall ?: false
            } else {
                // Below API 31 TelephonyManager.getCallState() is the only available signal.
                @Suppress("DEPRECATION")
                val manager = context.getSystemService(TelephonyManager::class.java) ?: return false
                @Suppress("DEPRECATION")
                when (manager.callState) {
                    TelephonyManager.CALL_STATE_OFFHOOK, TelephonyManager.CALL_STATE_RINGING -> true
                    else -> false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "busy signal: getCallState failed", e)
            false
        }
    }

    /** `true` while a DND/quiet interruption filter is active; never throws. */
    fun isDndActive(context: Context): Boolean = try {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    } catch (e: Exception) {
        Log.w(TAG, "busy signal: getCurrentInterruptionFilter failed", e)
        false
    }
}
