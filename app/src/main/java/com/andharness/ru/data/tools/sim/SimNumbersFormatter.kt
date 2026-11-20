package com.andharness.ru.data.tools.sim

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Pure, Android-free row mapping and honest-path messages for reading the SIM phone number(s)
 * (`sim_numbers`) and for the first-launch `sim_numbers` device fact.
 *
 * The Android side (`SimNumbersReader`) owns `SubscriptionManager`/`TelephonyManager` and the
 * permission checks; everything here is plain Kotlin so the subscription -> row mapping, the
 * "номер есть / номер не определён" decision and the JSON payload are covered by JVM tests.
 *
 * Honesty rule (BUG-056/BUG-057): when a number cannot be read it is NEVER invented or borrowed —
 * the row reports [UNDEFINED] and the payload carries a machine-readable `numberKnown:false` plus a
 * human note. On most SIMs/operators the MSISDN is simply not provisioned, so `не определено` is
 * the expected, honest answer.
 *
 * References:
 * - https://developer.android.com/reference/android/telephony/SubscriptionManager#getPhoneNumber(int)
 * - https://developer.android.com/reference/android/telephony/TelephonyManager#getLine1Number()
 */
object SimNumbersFormatter {

    /** Honest marker for "the platform did not return a number" (never a guessed value). */
    const val UNDEFINED = "не определено"

    const val NO_SIM_MESSAGE =
        "Активные SIM-подписки не найдены (0). Номер определить невозможно."
    const val NO_PERMISSION_MESSAGE =
        "Нет разрешения READ_PHONE_STATE — номера SIM недоступны. Данные не выдумываются."
    const val ICCID_UNAVAILABLE = "недоступен"

    const val HONEST_NOTE =
        "Номер не предоставлен оператором/SIM (MSISDN пуст). Android отдаёт «свой» номер, только " +
            "если оператор прописал его на SIM; чтение EF_MSISDN доступно лишь привилегированным " +
            "приложениям. Мульти-MSISDN (два номера на одной SIM) платформой не отдаётся."

    /** One human-readable line per active subscription; an empty list is reported honestly. */
    fun format(rows: List<SimNumberRow>): String {
        if (rows.isEmpty()) return NO_SIM_MESSAGE
        val header = "Номера SIM-подписок: ${rows.size}"
        val body = rows.map(::row)
        val note = if (rows.none { it.hasNumber }) "\n$HONEST_NOTE" else ""
        return (listOf(header) + body).joinToString("\n") + note
    }

    /** Honest message when `READ_PHONE_STATE` is not granted. */
    fun noPermission(): String = NO_PERMISSION_MESSAGE

    /**
     * The device-fact payload: one entry per subscription with the number when known and the
     * explicit [UNDEFINED] marker when not. `numberKnown` is `true` only if at least one real
     * number was returned.
     */
    fun toFactJson(rows: List<SimNumberRow>): JsonObject = buildJsonObject {
        put("count", rows.size)
        put("numberKnown", rows.any { it.hasNumber })
        if (rows.isNotEmpty() && rows.none { it.hasNumber }) put("note", HONEST_NOTE)
        putJsonArray("sims") {
            rows.forEach { row ->
                addJsonObject {
                    put("subscriptionId", row.subscriptionId)
                    put("slotIndex", row.slotIndex)
                    put("carrierName", row.carrierName?.trim().orEmpty())
                    put("iccid", row.iccid?.trim()?.takeIf { it.isNotEmpty() } ?: ICCID_UNAVAILABLE)
                    put("number", if (row.hasNumber) row.number!!.trim() else UNDEFINED)
                    put("numberKnown", row.hasNumber)
                    row.numberUnavailableReason?.let { put("numberUnavailableReason", it) }
                }
            }
        }
    }

    private fun row(row: SimNumberRow): String {
        val carrier = row.carrierName?.trim()?.takeIf { it.isNotEmpty() } ?: "(оператор неизвестен)"
        val iccid = row.iccid?.trim()?.takeIf { it.isNotEmpty() } ?: ICCID_UNAVAILABLE
        val number = when {
            row.hasNumber -> row.number!!.trim()
            row.numberUnavailableReason != null -> "$UNDEFINED (${row.numberUnavailableReason})"
            else -> UNDEFINED
        }
        return "slot=${row.slotIndex} | id=${row.subscriptionId} | $carrier | ICCID $iccid | номер: $number"
    }
}
