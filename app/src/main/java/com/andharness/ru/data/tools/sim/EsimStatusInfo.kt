package com.andharness.ru.data.tools.sim

/**
 * Android-free snapshot of the eUICC/eSIM state, built by `EsimStatusToolHandler` from a real
 * `android.telephony.euicc.EuiccManager`.
 *
 * [eidBlockedReason] is non-null when the EID could not be read (for example a `SecurityException`
 * from missing carrier privileges) — the EID is then `null` and is never fabricated. [osVersion]
 * comes from `EuiccInfo.getOsVersion()`.
 *
 * Official reference:
 * https://developer.android.com/reference/android/telephony/euicc/EuiccManager
 */
data class EsimStatusInfo(
    val supported: Boolean,
    val enabled: Boolean,
    val eid: String?,
    val eidBlockedReason: String?,
    val osVersion: String?,
)
