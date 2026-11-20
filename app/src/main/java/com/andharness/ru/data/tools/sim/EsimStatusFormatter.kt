package com.andharness.ru.data.tools.sim

/**
 * Pure, Android-free formatting for `esim_status`.
 *
 * Honesty contract: `EuiccManager.getEid()` requires carrier privileges and throws
 * `SecurityException` otherwise; `getEuiccInfo()` may return null when the eUICC is not ready. The
 * formatter never invents an EID — a blocked read is reported with the carrier-privilege reason.
 * `isEnabled()` itself needs no permission but throws `UnsupportedOperationException` on devices
 * without the eUICC feature.
 *
 * https://developer.android.com/reference/android/telephony/euicc/EuiccManager#getEid()
 * https://developer.android.com/reference/android/telephony/euicc/EuiccManager#getEuiccInfo()
 * https://developer.android.com/reference/android/telephony/euicc/EuiccManager#isEnabled()
 */
object EsimStatusFormatter {

    const val CARRIER_PRIVILEGE_REASON =
        "недоступно: требуются привилегии оператора (carrier privileges)"

    fun format(info: EsimStatusInfo): String = buildString {
        appendLine("eSIM поддерживается: ${yesNo(info.supported)}")
        appendLine("eSIM включена: ${yesNo(info.enabled)}")
        append("EID: ")
        when {
            !info.supported -> append("недоступно (устройство без eUICC)")
            info.eid != null -> append(info.eid)
            info.eidBlockedReason != null -> append(info.eidBlockedReason)
            else -> append("недоступно (eUICC не готов)")
        }
        appendLine()
        append("Версия eUICC OS: ${info.osVersion?.takeIf { it.isNotBlank() } ?: "недоступно"}")
    }

    private fun yesNo(value: Boolean): String = if (value) "да" else "нет"
}
