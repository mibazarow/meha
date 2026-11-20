package com.andharness.ru.domain.calls


/**
 * User-defined rules for automatic call/SMS handling (checklist §1, «Авто-обработка звонков/SMS»):
 * reject spam/unknown callers, auto-reply when unavailable/busy and a "call back" reminder.
 *
 * The model is deliberately **Android-free** (only `kotlinx.serialization`), so the whole policy
 * ([CallRuleEngine.decideCall] / [CallRuleEngine.shouldAutoReply]) and its codec are exercised on the
 * JVM; the Android-bound [com.andharness.ru.data.calls.CallRulesStore] only adds file I/O, and the
 * call-screening service only feeds it the real caller number / contact state.
 *
 * ## Safety model (why this is safe to ship)
 * - **Opt-in**: [CallRules.enabled] defaults to `false`; with an empty/missing file nothing is
 *   auto-handled and every call is allowed exactly as before the feature existed.
 * - **Auto-reply is never sent to** short codes, emergency numbers, service/800 numbers or
 *   alphanumeric sender ids ([isReplyEligible]), nor to allowlisted or known contacts
 *   ([CallRuleEngine.shouldAutoReply] / the allowlist precedence in [CallRuleEngine.decideCall]).
 * - **Cooldown**: at most one auto-reply per number per [AutoReplyCooldown.DEFAULT_COOLDOWN_MS].
 *
 * ## Platform references
 * - `CallScreeningService` / `CallResponse` (allow vs. disallow/reject a call):
 *   https://developer.android.com/reference/android/telecom/CallScreeningService
 * - `CallScreeningService.CallResponse.Builder#setDisallowCall(boolean)` /
 *   `setRejectCall(boolean)`:
 *   https://developer.android.com/reference/android/telecom/CallScreeningService.CallResponse.Builder
 * - SMS sending limits and the "premium/short-code" restriction (apps may not send SMS to premium
 *   numbers; the platform enforces it via `SmsManager`):
 *   https://developer.android.com/guide/topics/permissions/default-handlers
 *   https://developer.android.com/reference/android/telephony/SmsManager#sendTextMessage(java.lang.String,%20java.lang.String,%20java.lang.String,%20android.app.PendingIntent,%20android.content.Intent)
 * - Emergency numbers (`TelephonyManager.EMERGENCY_NUMBER_PREFIX` is carrier-defined; the canonical
 *   GSM/EU emergency number is 112):
 *   https://developer.android.com/reference/android/telephony/TelephonyManager#isEmergencyNumber(java.lang.String)
 */
enum class CallDecision {
    /** Let the call through unchanged (no SMS, no reminder). */
    ALLOW,

    /** Disallow/reject the call (no SMS is implied by this decision itself). */
    REJECT,

    /** Let the call through AND send the configured auto-reply SMS to the caller. */
    ALLOW_AND_REPLY,
}
