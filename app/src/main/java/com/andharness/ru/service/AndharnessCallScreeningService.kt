package com.andharness.ru.service

import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import com.andharness.ru.data.intercept.ContactLookup
import com.andharness.ru.data.intercept.NumberChatManager
import com.andharness.ru.data.intercept.interceptEntryPoint
import com.andharness.ru.domain.calls.CallDecision
import com.andharness.ru.domain.calls.CallRuleEngine
import com.andharness.ru.domain.calls.CallRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Real `CallScreeningService` (update.md §2.2) that now **applies the user's call rules** (checklist §1).
 *
 * Contract:
 * - **Opt-in, default OFF is a strict no-op** — when [CallRules.enabled] is `false` (the default,
 *   including a missing/corrupt rules file or an unavailable Hilt entry point) the response is the
 *   historical allow, and nothing else changes.
 * - **When enabled** the response is decided by the pure [CallRuleEngine.decideCall]:
 *   - `ALLOW` / `ALLOW_AND_REPLY` → the call is allowed (no disallow/reject/silence);
 *   - `REJECT` → the call is disallowed **and** rejected
 *     (`setDisallowCall(true)` + `setRejectCall(true)`; reject/silence are ignored unless the call is
 *     disallowed).
 *   The allowlist always passes; explicitly blocked numbers are rejected; `blockUnknown` rejects a
 *   caller that does not resolve to a contact.
 * - **The response is immediate and never fatal** — rule evaluation is wrapped so any failure falls
 *   back to allow, and every heavy side effect (auto-reply SMS, call-back reminder, OSINT chat) runs
 *   asynchronously AFTER [respondToCall], so the platform's ~5 s deadline is never at risk.
 * - **Role-gated** — the system only calls this service when the app holds `ROLE_CALL_SCREENING`
 *   (requested by the existing role flow); if the role is not held, nothing happens here and the
 *   delayed call-log fallback ([com.andharness.ru.bg.CallLogWatcherWorker]) covers the gap honestly.
 *
 * Manifest (already declared): `android:permission="android.permission.BIND_SCREENING_SERVICE"`,
 * exported, with the `android.telecom.CallScreeningService` intent filter.
 *
 * References:
 * - `CallScreeningService` / `onScreenCall` / `respondToCall` / `CallResponse`:
 *   https://developer.android.com/reference/android/telecom/CallScreeningService
 * - `CallScreeningService.CallResponse.Builder#setDisallowCall(boolean)` /
 *   `setRejectCall(boolean)` / `setSilenceCall(boolean)`:
 *   https://developer.android.com/reference/android/telecom/CallScreeningService.CallResponse.Builder
 * - `RoleManager.ROLE_CALL_SCREENING`:
 *   https://developer.android.com/reference/android/app/role/RoleManager#ROLE_CALL_SCREENING
 * - Response deadline (the platform gives ~5 s):
 *   https://developer.android.com/reference/android/telecom/CallScreeningService#onScreenCall(android.telecom.Call.Details)
 */
class AndharnessCallScreeningService : CallScreeningService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() }

        // Resolve the rules best-effort. ANY failure degrades to the opt-in no-op (allow), exactly the
        // historical behaviour, so a broken entry point can never reject a call.
        val entry = try {
            interceptEntryPoint(this)
        } catch (e: Exception) {
            Log.e(TAG, "Hilt entry point unavailable", e)
            null
        }
        val rules = try {
            entry?.callRulesStore()?.current()
        } catch (e: Exception) {
            Log.e(TAG, "call rules unavailable; allowing the call", e)
            null
        } ?: CallRules()

        val contactKnown = if (rules.enabled && number != null) {
            try {
                // A single bounded ContactsContract.PhoneLookup query; missing READ_CONTACTS is logged
                // by ContactLookup and means "unknown".
                ContactLookup.isKnown(this, number)
            } catch (e: Exception) {
                Log.w(TAG, "contact lookup failed during screening", e)
                false
            }
        } else {
            false
        }

        val decision = try {
            CallRuleEngine.decideCall(number, contactKnown, rules)
        } catch (e: Exception) {
            Log.e(TAG, "decideCall failed; allowing the call", e)
            CallDecision.ALLOW
        }

        val response = buildResponse(decision)
        try {
            respondToCall(callDetails, response)
        } catch (e: Exception) {
            Log.e(TAG, "respondToCall failed", e)
            return
        }
        if (decision == CallDecision.REJECT) {
            Log.i(TAG, "call from $number rejected by the call rules")
        }

        if (number == null || entry == null) return

        // Auto-reply SMS: for an allowed-and-replied call, or a rejected unknown spam caller the
        // independent reply gate approves. Never blocks the response above.
        val shouldReply = decision == CallDecision.ALLOW_AND_REPLY ||
            (decision == CallDecision.REJECT && CallRuleEngine.shouldAutoReply(contactKnown, rules))
        if (shouldReply) {
            scope.launch {
                try {
                    val outcome = entry.callSmsRuleApplier().autoReplyToCaller(number)
                    Log.i(TAG, "call auto-reply: ${outcome.note}")
                } catch (e: Exception) {
                    Log.e(TAG, "call auto-reply failed", e)
                }
            }
        }

        // Call-back reminder: a rejected call from a KNOWN contact with the flag on.
        if (decision == CallDecision.REJECT && contactKnown && rules.callBackReminder) {
            scope.launch {
                try {
                    val name = ContactLookup.displayName(this@AndharnessCallScreeningService, number)
                    val outcome = entry.callSmsRuleApplier()
                        .maybeCreateCallBackReminder(number, name, rejectedOrMissed = true)
                    Log.i(TAG, "call-back reminder: ${outcome.note}")
                } catch (e: Exception) {
                    Log.e(TAG, "call-back reminder failed", e)
                }
            }
        }

        if (!entry.watchdogConfigStore().isInterceptionEnabled()) return

        scope.launch {
            try {
                val outcome = entry.numberChatManager().onEvent(
                    rawNumber = number,
                    eventKind = NumberChatManager.EVENT_CALL,
                )
                Log.i(TAG, "call screening post-processing: ${outcome.note}")
            } catch (e: Exception) {
                Log.e(TAG, "async call post-processing failed", e)
            }
        }
    }

    /**
     * Builds the documented [CallResponse]. A reject must also disallow the call (per the platform
     * contract reject/silence only take effect on a disallowed call); everything else is the plain
     * allow used before this feature existed.
     */
    private fun buildResponse(decision: CallDecision): CallResponse =
        if (decision == CallDecision.REJECT) {
            CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSilenceCall(false)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build()
        } else {
            CallResponse.Builder()
                .setDisallowCall(false)
                .setRejectCall(false)
                .setSilenceCall(false)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build()
        }

    companion object {
        private const val TAG = "Andharness"
    }
}
