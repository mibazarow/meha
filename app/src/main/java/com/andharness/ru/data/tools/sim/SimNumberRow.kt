package com.andharness.ru.data.tools.sim

/**
 * Android-free snapshot of the phone number known for one active subscription.
 *
 * Built by `SimNumbersReader`; keeping the model plain Kotlin lets the honest mapping
 * (number present vs. undetermined) be covered by JVM tests without an Android runtime.
 *
 * Official references:
 * - https://developer.android.com/reference/android/telephony/SubscriptionManager#getPhoneNumber(int)
 *   (`getPhoneNumber(subId)`, API 33+, needs `READ_PHONE_NUMBERS`)
 * - https://developer.android.com/reference/android/telephony/TelephonyManager#getLine1Number()
 *   (needs `READ_PHONE_STATE` or `READ_PHONE_NUMBERS`)
 * - https://developer.android.com/reference/android/telephony/SubscriptionInfo#getIccId()
 *   (carrier-privilege gated since API 29; usually `null` for an ordinary app)
 */
data class SimNumberRow(
    val subscriptionId: Int,
    val slotIndex: Int,
    val carrierName: String?,
    val iccid: String?,
    /**
     * The number reported by the platform, or `null` when the SIM/operator does not expose it
     * (the common case). Never a fabricated value.
     */
    val number: String?,
    /** Why the number is unavailable (e.g. missing `READ_PHONE_NUMBERS`), if known. */
    val numberUnavailableReason: String? = null,
) {
    /** True only when a non-blank number was actually returned by the platform. */
    val hasNumber: Boolean get() = !number.isNullOrBlank()
}
