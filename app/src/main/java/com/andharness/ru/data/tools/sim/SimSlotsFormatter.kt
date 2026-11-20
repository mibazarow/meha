package com.andharness.ru.data.tools.sim

/**
 * Pure, Android-free row mapping and honest-path messages for the SIM read tools
 * (`sim_slots_list`).
 *
 * The Android handler owns `SubscriptionManager`/`SubscriptionInfo` and the permission checks;
 * everything here is plain Kotlin so the subscription -> row mapping, the default-SIM decision and
 * the honest "нет разрешения" / "нет SIM" paths are covered by JVM tests.
 *
 * Default-role getters (`getDefaultVoiceSubscriptionId`, `getDefaultSmsSubscriptionId`,
 * `getDefaultDataSubscriptionId`) are public static and return
 * `SubscriptionManager.INVALID_SUBSCRIPTION_ID` (-1) when unset:
 * https://developer.android.com/reference/android/telephony/SubscriptionManager#getDefaultVoiceSubscriptionId()
 * https://developer.android.com/reference/android/telephony/SubscriptionManager#getDefaultSmsSubscriptionId()
 * https://developer.android.com/reference/android/telephony/SubscriptionManager#getDefaultDataSubscriptionId()
 */
object SimSlotsFormatter {

    const val NO_SIM_MESSAGE = "Активные SIM-подписки не найдены (0). На устройстве нет SIM или eSIM."
    const val NO_PERMISSION_MESSAGE =
        "Нет разрешения READ_PHONE_STATE — список SIM недоступен. Данные не выдумываются."

    /** One row per active subscription; an empty list is reported honestly. */
    fun format(slots: List<SimSlotInfo>): String {
        if (slots.isEmpty()) return NO_SIM_MESSAGE
        val header = "SIM-подписок: ${slots.size}"
        return (listOf(header) + slots.map(::row)).joinToString("\n")
    }

    /** Honest message when `READ_PHONE_STATE` is not granted. */
    fun noPermission(): String = NO_PERMISSION_MESSAGE

    private fun row(slot: SimSlotInfo): String {
        val carrier = slot.carrierName?.trim()?.takeIf { it.isNotEmpty() } ?: "(оператор неизвестен)"
        val display = slot.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: "(без имени)"
        val type = if (slot.embedded) "eSIM" else "физическая"
        val defaults = buildList {
            if (slot.isDefaultVoice) add("звонки")
            if (slot.isDefaultSms) add("SMS")
            if (slot.isDefaultData) add("данные")
        }.ifEmpty { listOf("нет") }.joinToString("/")
        return "slot=${slot.slotIndex} | id=${slot.subscriptionId} | $type | $carrier | имя: $display | по умолчанию: $defaults"
    }
}
