package com.andharness.ru.domain.tools


/**
 * Pure decision + honest wording for the `call_start` tool (update.md bug #6: «позвони» must
 * actually PLACE the call, not merely open the dialer).
 *
 * Android-free by design so the matrix (blank number, missing `CALL_PHONE`, emergency number,
 * which API was used) is unit-tested in [com.andharness.ru.domain.tools.CallStartPlannerTest].
 *
 * The actual placement lives in
 * [com.andharness.ru.data.tools.calls.CallsToolsHandler]:
 * `TelecomManager.placeCall(Uri, Bundle)` first (system telecom service, works without starting an
 * Activity — https://developer.android.com/reference/android/telecom/TelecomManager#placeCall(android.net.Uri,%20android.os.Bundle)),
 * then `Intent.ACTION_CALL` as the documented fallback
 * (https://developer.android.com/guide/components/intents-actions#Make_a_phone_call).
 * `Intent.ACTION_DIAL` is deliberately NOT used here: it never places a call.
 */
enum class CallApi(val label: String) {
    /** `TelecomManager.placeCall(Uri, Bundle)` — the system telecom service places the call. */
    TELECOM_PLACE_CALL("TelecomManager.placeCall"),

    /** `Intent.ACTION_CALL` — the platform telecom stack places the call. */
    ACTION_CALL("Intent.ACTION_CALL"),

    /** Fallback that only opens the dialer; never reported as a successful call. */
    DIAL_ONLY("Intent.ACTION_DIAL"),
}
