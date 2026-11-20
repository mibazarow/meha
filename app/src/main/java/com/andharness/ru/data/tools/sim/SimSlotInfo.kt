package com.andharness.ru.data.tools.sim

/**
 * Android-free snapshot of one SIM slot/subscription.
 *
 * Built by `SimSlotsListToolHandler` from a real `android.telephony.SubscriptionInfo`; keeping the
 * model plain Kotlin lets the row mapping and the default-SIM decision be covered by JVM tests
 * without an Android runtime.
 *
 * Official reference:
 * https://developer.android.com/reference/android/telephony/SubscriptionInfo
 */
data class SimSlotInfo(
    val subscriptionId: Int,
    val slotIndex: Int,
    val carrierName: String?,
    val displayName: String?,
    val embedded: Boolean,
    val isDefaultVoice: Boolean,
    val isDefaultSms: Boolean,
    val isDefaultData: Boolean,
)
